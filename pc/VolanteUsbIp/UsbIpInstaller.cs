using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace VolanteUsbIp
{
    /// <summary>
    /// Downloads and runs the latest usbip-win2 installer from GitHub. Not winget: as of 2026-10 winget
    /// still serves 0.9.7.8, which its author flags as able to cause memory corruption and BSOD.
    /// </summary>
    public static class UsbIpInstaller
    {
        const string LatestApi = "https://api.github.com/repos/vadimgrn/usbip-win2/releases/latest";
        public const string ReleasesPage = "https://github.com/vadimgrn/usbip-win2/releases/latest";

        /// <summary>Returns (version, installer path).</summary>
        public static Task<(string version, string path)> DownloadLatestAsync(Action<string> log) => Task.Run(() =>
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            using (var web = new WebClient())
            {
                web.Headers[HttpRequestHeader.UserAgent] = "VolanteUsbIp";
                var json = web.DownloadString(LatestApi);
                var tag = Regex.Match(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"").Groups[1].Value;
                var arch = RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "arm64" : "x64";
                var url = Regex.Match(json, "\"browser_download_url\"\\s*:\\s*\"([^\"]+-" + arch + "\\.exe)\"").Groups[1].Value;
                if (string.IsNullOrEmpty(url))
                    throw new InvalidOperationException("No encontré el instalador " + arch + " en el último release de usbip-win2.");
                if (tag.Contains("0.9.7.8"))
                    throw new InvalidOperationException("El último release es 0.9.7.8, que tiene un error conocido de pantalla azul. Instala otra versión a mano.");
                var file = Path.Combine(Path.GetTempPath(), url.Substring(url.LastIndexOf('/') + 1));
                log($"Descargando {Path.GetFileName(file)}…");
                web.DownloadFile(url, file);
                return (tag, file);
            }
        });

        /// <summary>Runs the installer (it asks for administrator rights) and waits for it.</summary>
        public static Task<int> RunAsync(string installer) => Task.Run(() =>
        {
            using (var p = Process.Start(new ProcessStartInfo(installer) { UseShellExecute = true }))
            {
                p.WaitForExit();
                return p.ExitCode;
            }
        });
    }
}
