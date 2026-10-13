using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace VolanteUsbIp
{
    public class RemoteDevice
    {
        public string BusId;
        public string Description;
        public string VendorId;
        public string ProductId;
        public bool IsLogitech => VendorId == "046d";
        public override string ToString() => $"{BusId}  ·  {Description}  ({VendorId}:{ProductId})";
    }

    public class ImportedDevice
    {
        public int Port;
        public string Host;
        public string BusId;
        public string Product;
    }

    public class CliResult
    {
        public int ExitCode;
        public string Output;
        public bool Ok => ExitCode == 0;
    }

    /// <summary>Wrapper around usbip-win2's usbip.exe (output formats from userspace/usbip/*.cpp).</summary>
    public class UsbIpCli
    {
        public string ExePath { get; }

        public UsbIpCli(string exePath) { ExePath = exePath; }

        public static string Locate(string configured)
        {
            if (!string.IsNullOrWhiteSpace(configured) && File.Exists(configured)) return configured;
            var candidates = new[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "USBip", "usbip.exe"),
                Path.Combine(Environment.GetEnvironmentVariable("ProgramW6432") ?? "", "USBip", "usbip.exe"),
            };
            foreach (var c in candidates)
                if (File.Exists(c)) return c;
            foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            {
                try
                {
                    var p = Path.Combine(dir.Trim(), "usbip.exe");
                    if (File.Exists(p)) return p;
                }
                catch (ArgumentException) { }
            }
            return null;
        }

        public Task<CliResult> RunAsync(string args, int timeoutMs = 20000) => Task.Run(() =>
        {
            var psi = new ProcessStartInfo(ExePath, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            using (var p = new Process { StartInfo = psi })
            {
                var output = new StringBuilder();
                p.OutputDataReceived += (s, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                p.ErrorDataReceived += (s, e) => { if (e.Data != null) lock (output) output.AppendLine(e.Data); };
                p.Start();
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                if (!p.WaitForExit(timeoutMs))
                {
                    try { p.Kill(); } catch (InvalidOperationException) { }
                    return new CliResult { ExitCode = -1, Output = "usbip.exe no respondió a tiempo" };
                }
                p.WaitForExit(); // flush async readers
                lock (output) return new CliResult { ExitCode = p.ExitCode, Output = output.ToString().Trim() };
            }
        });

        // "    1-1    : Logitech, Inc. : G29 Driving Force Racing Wheel [PS4] (046d:c24f)"
        static readonly Regex DeviceLine = new Regex(
            @"^\s*(\d+-[\d.]+)\s*:\s*(.+?)\s*\(([0-9a-fA-F]{4}):([0-9a-fA-F]{4})\)\s*$", RegexOptions.Compiled);

        public async Task<(List<RemoteDevice> devices, CliResult result)> ListAsync(string host)
        {
            var r = await RunAsync($"list -r {host}");
            var list = new List<RemoteDevice>();
            if (r.Ok)
            {
                foreach (var line in r.Output.Split('\n'))
                {
                    var m = DeviceLine.Match(line.TrimEnd('\r'));
                    if (m.Success)
                        list.Add(new RemoteDevice
                        {
                            BusId = m.Groups[1].Value,
                            Description = m.Groups[2].Value,
                            VendorId = m.Groups[3].Value.ToLowerInvariant(),
                            ProductId = m.Groups[4].Value.ToLowerInvariant(),
                        });
                }
            }
            return (list, r);
        }

        static readonly Regex PortLine = new Regex(@"^Port\s+(\d+):", RegexOptions.Compiled);
        static readonly Regex UrlLine = new Regex(@"usbip://(.+):(\d+)/(\S+)", RegexOptions.Compiled);

        /// <summary>Parses "usbip port": Port 01 … / product line / -> usbip://host:3240/busid.</summary>
        public async Task<List<ImportedDevice>> PortsAsync()
        {
            var r = await RunAsync("port");
            var list = new List<ImportedDevice>();
            ImportedDevice current = null;
            bool expectProduct = false;
            foreach (var raw in r.Output.Split('\n'))
            {
                var line = raw.TrimEnd('\r');
                var pm = PortLine.Match(line);
                if (pm.Success)
                {
                    current = new ImportedDevice { Port = int.Parse(pm.Groups[1].Value) };
                    list.Add(current);
                    expectProduct = true;
                    continue;
                }
                if (current == null) continue;
                var um = UrlLine.Match(line);
                if (um.Success)
                {
                    current.Host = um.Groups[1].Value.Trim('[', ']');
                    current.BusId = um.Groups[3].Value;
                }
                else if (expectProduct && line.Trim().Length > 0)
                {
                    current.Product = line.Trim();
                }
                expectProduct = false;
            }
            return list;
        }

        /// <summary>
        /// Without --once, usbip-win2 keeps retrying in the driver if it cannot connect.
        /// low-latency (0.9.8+) suits HID devices that send small reports at high frequency.
        /// </summary>
        public Task<CliResult> AttachAsync(string host, string busId, bool lowLatency) =>
            RunAsync($"attach -r {host} -b {busId}" + (lowLatency ? " --receive-mode=low-latency" : ""));

        /// <summary>usbip-win2 keeps retrying failed attaches in the driver; this stops it.</summary>
        public Task<CliResult> StopAttemptsAsync(string host, string busId) => RunAsync($"attach -r {host} -b {busId} -x");

        public Task<CliResult> DetachAsync(int port) => RunAsync($"detach -p {port}");
    }
}
