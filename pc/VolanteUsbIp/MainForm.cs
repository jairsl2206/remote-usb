using System;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace VolanteUsbIp
{
    public class MainForm : Form
    {
        readonly Settings settings = Settings.Load();
        readonly bool startHidden;
        UsbIpCli cli;

        // What the user asked for: keep the wheel mounted until "Desconectar".
        bool wantConnected;
        bool busy;
        bool exiting;
        string lastState = "";
        bool attachFailureLogged;

        readonly Timer timer = new Timer();
        readonly NotifyIcon tray = new NotifyIcon();

        // Controls
        readonly TextBox hostBox = new TextBox { Width = 220 };
        readonly ComboBox deviceBox = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 420 };
        readonly Button searchButton = new Button { Text = "Buscar", AutoSize = true };
        readonly Button connectButton = new Button { Text = "Conectar", AutoSize = true };
        readonly Button disconnectButton = new Button { Text = "Desconectar", AutoSize = true };
        readonly Label statusDot = new Label { Text = "●", AutoSize = true, Font = new Font("Segoe UI", 16f) };
        readonly Label statusLabel = new Label { AutoSize = true, Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
        readonly CheckBox connectOnStartBox = new CheckBox { Text = "Conectar al abrir el programa", AutoSize = true };
        readonly CheckBox autoReconnectBox = new CheckBox { Text = "Reconectar automáticamente", AutoSize = true };
        readonly NumericUpDown intervalBox = new NumericUpDown { Minimum = 1, Maximum = 60, Width = 50 };
        readonly CheckBox startWithWindowsBox = new CheckBox { Text = "Iniciar con Windows (en la bandeja)", AutoSize = true };
        readonly CheckBox startMinimizedBox = new CheckBox { Text = "Abrir minimizado en la bandeja", AutoSize = true };
        readonly TextBox usbipPathBox = new TextBox { Width = 330 };
        readonly Button browseButton = new Button { Text = "…", Width = 32 };
        readonly LinkLabel installLink = new LinkLabel { Text = "Instalar usbip-win2", AutoSize = true, Margin = new Padding(3, 7, 3, 3) };
        readonly CheckBox lowLatencyBox = new CheckBox { Text = "Recepción de baja latencia (usbip-win2 0.9.8 o posterior)", AutoSize = true };
        readonly TextBox logBox = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill,
            Font = new Font("Consolas", 9f), BackColor = SystemColors.Window,
        };

        public MainForm(bool startHidden)
        {
            this.startHidden = startHidden || settings.StartMinimized;
            Text = "Volante USB/IP";
            Icon = LoadIcon();
            Font = new Font("Segoe UI", 9.5f);
            AutoScaleMode = AutoScaleMode.Dpi;
            MinimumSize = new Size(640, 560);
            Size = new Size(720, 640);
            StartPosition = FormStartPosition.CenterScreen;

            BuildLayout();
            BuildTray();
            LoadSettingsIntoControls();

            searchButton.Click += async (s, e) => await SearchAsync();
            connectButton.Click += async (s, e) => await ConnectAsync();
            disconnectButton.Click += async (s, e) => await DisconnectAsync();
            browseButton.Click += (s, e) => BrowseUsbIp();
            installLink.LinkClicked += async (s, e) => await InstallUsbIpAsync();
            foreach (var c in new Control[] { connectOnStartBox, autoReconnectBox, startMinimizedBox, lowLatencyBox })
                ((CheckBox)c).CheckedChanged += (s, e) => SaveSettings();
            intervalBox.ValueChanged += (s, e) => { SaveSettings(); timer.Interval = (int)intervalBox.Value * 1000; };
            startWithWindowsBox.CheckedChanged += (s, e) => Settings.StartWithWindows = startWithWindowsBox.Checked;
            usbipPathBox.Leave += (s, e) => { SaveSettings(); RefreshCli(); };
            hostBox.Leave += (s, e) => SaveSettings();

            timer.Interval = Math.Max(1, settings.CheckIntervalSeconds) * 1000;
            timer.Tick += async (s, e) => await TickAsync();

            Load += (s, e) => StartUp();
        }

        bool started;

        async void StartUp()
        {
            if (started) return;
            started = true;
            RefreshCli();
            Log($"Volante USB/IP {Application.ProductVersion}");
            if (settings.ConnectOnStart && IsValidHost(settings.TvHost)) wantConnected = true;
            timer.Start();
            await TickAsync();
        }

        // ---------------------------------------------------------------- UI

        void BuildLayout()
        {
            var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(12) };
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

            // Status
            var status = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 0, 0, 8) };
            statusLabel.Margin = new Padding(0, 8, 0, 0);
            status.Controls.Add(statusDot);
            status.Controls.Add(statusLabel);
            root.Controls.Add(status);

            // Connection
            var conn = new GroupBox { Text = "Conexión", Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(10) };
            var cg = Grid(2);
            cg.Controls.Add(Caption("IP de la TV:"), 0, 0);
            var hostRow = Row(hostBox, searchButton);
            cg.Controls.Add(hostRow, 1, 0);
            cg.Controls.Add(Caption("Dispositivo:"), 0, 1);
            cg.Controls.Add(deviceBox, 1, 1);
            cg.Controls.Add(new Label(), 0, 2);
            cg.Controls.Add(Row(connectButton, disconnectButton), 1, 2);
            conn.Controls.Add(cg);
            root.Controls.Add(conn);

            // Options
            var opts = new GroupBox { Text = "Opciones", Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(10) };
            var og = Grid(2);
            var reconnectRow = Row(autoReconnectBox, Caption("  revisar cada"), intervalBox, Caption("s"));
            og.Controls.Add(connectOnStartBox, 0, 0);
            og.SetColumnSpan(connectOnStartBox, 2);
            og.Controls.Add(reconnectRow, 0, 1);
            og.SetColumnSpan(reconnectRow, 2);
            og.Controls.Add(startWithWindowsBox, 0, 2);
            og.SetColumnSpan(startWithWindowsBox, 2);
            og.Controls.Add(startMinimizedBox, 0, 3);
            og.SetColumnSpan(startMinimizedBox, 2);
            og.Controls.Add(lowLatencyBox, 0, 4);
            og.SetColumnSpan(lowLatencyBox, 2);
            og.Controls.Add(Caption("usbip.exe:"), 0, 5);
            og.Controls.Add(Row(usbipPathBox, browseButton, installLink), 1, 5);
            opts.Controls.Add(og);
            root.Controls.Add(opts);

            // Log
            var logGroup = new GroupBox { Text = "Registro", Dock = DockStyle.Fill, Padding = new Padding(8) };
            logGroup.Controls.Add(logBox);
            root.Controls.Add(logGroup);

            Controls.Add(root);
            AcceptButton = connectButton;
        }

        static TableLayoutPanel Grid(int columns)
        {
            var g = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = columns };
            g.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            g.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            return g;
        }

        static FlowLayoutPanel Row(params Control[] controls)
        {
            var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0) };
            row.Controls.AddRange(controls);
            return row;
        }

        static Label Caption(string text) =>
            new Label { Text = text, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(3, 7, 3, 3) };

        void BuildTray()
        {
            tray.Icon = Icon;
            tray.Text = "Volante USB/IP";
            tray.Visible = true;
            var menu = new ContextMenuStrip();
            menu.Items.Add("Abrir", null, (s, e) => ShowFromTray());
            menu.Items.Add("Conectar", null, async (s, e) => await ConnectAsync());
            menu.Items.Add("Desconectar", null, async (s, e) => await DisconnectAsync());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Salir", null, (s, e) => ExitApp());
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (s, e) => ShowFromTray();
        }

        static Icon LoadIcon()
        {
            using (var s = typeof(MainForm).Assembly.GetManifestResourceStream("app.ico"))
                return s != null ? new Icon(s) : SystemIcons.Application;
        }

        protected override void SetVisibleCore(bool value)
        {
            // Start in the tray without flashing the window.
            if (startHidden && !IsHandleCreated)
            {
                CreateHandle();
                value = false;
                StartUp();
            }
            base.SetVisibleCore(value);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Closing the window keeps the wheel mounted from the tray; "Salir" really exits.
            if (!exiting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                tray.ShowBalloonTip(3000, "Volante USB/IP", "Sigue funcionando en la bandeja. Clic derecho → Salir para cerrarlo.", ToolTipIcon.Info);
                return;
            }
            tray.Visible = false;
            base.OnFormClosing(e);
        }

        void ShowFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
        }

        void ExitApp()
        {
            exiting = true;
            Close();
        }

        void Log(string message)
        {
            var line = $"{DateTime.Now:HH:mm:ss}  {message}";
            if (logBox.TextLength > 200_000) logBox.Text = logBox.Text.Substring(100_000);
            logBox.AppendText(line + Environment.NewLine);
        }

        void SetState(string text, Color color)
        {
            statusLabel.Text = text;
            statusDot.ForeColor = color;
            var tip = "Volante USB/IP: " + text;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip; // NotifyIcon limit
            if (text != lastState && !Visible && lastState != "")
                tray.ShowBalloonTip(3000, "Volante USB/IP", text, ToolTipIcon.Info);
            lastState = text;
        }

        // ---------------------------------------------------------------- settings

        void LoadSettingsIntoControls()
        {
            hostBox.Text = settings.TvHost;
            connectOnStartBox.Checked = settings.ConnectOnStart;
            autoReconnectBox.Checked = settings.AutoReconnect;
            intervalBox.Value = Math.Max(1, Math.Min(60, settings.CheckIntervalSeconds));
            startMinimizedBox.Checked = settings.StartMinimized;
            lowLatencyBox.Checked = settings.LowLatency;
            startWithWindowsBox.Checked = Settings.StartWithWindows;
            usbipPathBox.Text = settings.UsbIpPath;
            deviceBox.Items.Add(AutoDevice);
            if (!string.IsNullOrEmpty(settings.BusId))
            {
                deviceBox.Items.Add(new RemoteDevice { BusId = settings.BusId, Description = "(guardado)", VendorId = "????", ProductId = "????" });
                deviceBox.SelectedIndex = 1;
            }
            else
            {
                deviceBox.SelectedIndex = 0;
            }
            deviceBox.SelectedIndexChanged += (s, e) => SaveSettings();
        }

        static readonly object AutoDevice = "Automático: primer volante Logitech";

        void SaveSettings()
        {
            settings.TvHost = hostBox.Text.Trim();
            settings.BusId = deviceBox.SelectedItem is RemoteDevice d ? d.BusId : "";
            settings.ConnectOnStart = connectOnStartBox.Checked;
            settings.AutoReconnect = autoReconnectBox.Checked;
            settings.CheckIntervalSeconds = (int)intervalBox.Value;
            settings.StartMinimized = startMinimizedBox.Checked;
            settings.LowLatency = lowLatencyBox.Checked;
            settings.UsbIpPath = usbipPathBox.Text.Trim();
            try { settings.Save(); }
            catch (Exception ex) { Log("No se pudo guardar la configuración: " + ex.Message); }
        }

        bool missingLogged;

        /// <summary>Looks for usbip.exe; called at start-up and on every tick until it appears.</summary>
        void RefreshCli()
        {
            var path = UsbIpCli.Locate(settings.UsbIpPath);
            var wasMissing = cli == null;
            cli = path != null ? new UsbIpCli(path) : null;
            installLink.Visible = cli == null;
            if (cli == null)
            {
                SetState("Falta instalar usbip-win2", Color.Firebrick);
                if (!missingLogged)
                {
                    Log("No encuentro usbip.exe. Pulsa \"Instalar usbip-win2\" en Opciones (descarga la última versión de GitHub) o indica su ruta.");
                    missingLogged = true;
                }
                return;
            }
            if (wasMissing && missingLogged)
            {
                Log("usbip-win2 detectado: " + path);
                SetState("Desconectado", Color.Gray);
            }
            missingLogged = false;
            if (string.IsNullOrEmpty(usbipPathBox.Text)) usbipPathBox.Text = path;
        }

        void BrowseUsbIp()
        {
            using (var dlg = new OpenFileDialog { Filter = "usbip.exe|usbip.exe", Title = "Ubicación de usbip.exe" })
            {
                if (dlg.ShowDialog(this) != DialogResult.OK) return;
                usbipPathBox.Text = dlg.FileName;
                SaveSettings();
                RefreshCli();
            }
        }

        async Task InstallUsbIpAsync()
        {
            var answer = MessageBox.Show(this,
                "Se descargará e instalará la última versión de usbip-win2 desde GitHub (vadimgrn/usbip-win2).\n\n" +
                "• Windows pedirá permisos de administrador.\n" +
                "• Durante la instalación se reinician los hubs USB 3.0: teclado, mouse y otros USB se cortan unos segundos.\n" +
                "• Conviene crear antes un punto de restauración.\n\n¿Continuar?",
                "Instalar usbip-win2", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
            if (answer != DialogResult.OK) return;
            installLink.Enabled = false;
            try
            {
                var (version, file) = await UsbIpInstaller.DownloadLatestAsync(Log);
                Log($"Ejecutando el instalador de usbip-win2 {version}…");
                var code = await UsbIpInstaller.RunAsync(file);
                Log(code == 0 ? "Instalación terminada." : $"El instalador terminó con código {code}.");
                RefreshCli();
            }
            catch (Exception ex)
            {
                Log("No se pudo instalar automáticamente: " + ex.Message);
                Log("Descárgalo a mano: " + UsbIpInstaller.ReleasesPage);
                Process.Start(new ProcessStartInfo(UsbIpInstaller.ReleasesPage) { UseShellExecute = true });
            }
            finally
            {
                installLink.Enabled = true;
            }
        }

        // Hostnames/IPs only: the value ends up on usbip.exe's command line.
        static bool IsValidHost(string host) =>
            !string.IsNullOrWhiteSpace(host) && Regex.IsMatch(host, @"^[A-Za-z0-9.\-:\[\]]{1,253}$");

        string Host => hostBox.Text.Trim();

        bool CheckReady()
        {
            if (cli == null) { RefreshCli(); if (cli == null) return false; }
            if (!IsValidHost(Host))
            {
                Log("Escribe la IP de la TV (aparece en la pantalla de la app en la TV).");
                hostBox.Focus();
                return false;
            }
            return true;
        }

        // ---------------------------------------------------------------- actions

        async Task SearchAsync()
        {
            if (!CheckReady()) return;
            SaveSettings();
            searchButton.Enabled = false;
            try
            {
                Log($"Buscando dispositivos en {Host}…");
                var (devices, result) = await cli.ListAsync(Host);
                if (!result.Ok)
                {
                    Log("No se pudo listar: " + result.Output);
                    Log("¿La app está abierta en la TV y ambos equipos están en la misma red?");
                    return;
                }
                var keep = deviceBox.SelectedItem as RemoteDevice;
                deviceBox.Items.Clear();
                deviceBox.Items.Add(AutoDevice);
                foreach (var d in devices) deviceBox.Items.Add(d);
                var again = devices.FirstOrDefault(d => keep != null && d.BusId == keep.BusId);
                deviceBox.SelectedItem = (object)again ?? AutoDevice;
                Log(devices.Count == 0
                    ? "La TV no exporta dispositivos disponibles (¿permiso USB concedido? ¿ya está montado?)."
                    : $"{devices.Count} dispositivo(s): " + string.Join("; ", devices));
            }
            finally
            {
                searchButton.Enabled = true;
            }
        }

        async Task ConnectAsync()
        {
            if (!CheckReady()) return;
            SaveSettings();
            wantConnected = true;
            attachFailureLogged = false;
            Log($"Conectando con {Host}…");
            await TickAsync();
        }

        async Task DisconnectAsync()
        {
            wantConnected = false;
            if (cli == null || busy) { wantConnected = false; return; }
            busy = true;
            try
            {
                var host = Host;
                var ports = await cli.PortsAsync();
                foreach (var p in ports.Where(p => SameHost(p.Host, host)))
                {
                    var r = await cli.DetachAsync(p.Port);
                    Log(r.Ok ? $"Desmontado {p.BusId} (puerto {p.Port})" : "Error al desmontar: " + r.Output);
                    await cli.StopAttemptsAsync(host, p.BusId);
                }
                var busId = deviceBox.SelectedItem is RemoteDevice d ? d.BusId : null;
                if (busId != null) await cli.StopAttemptsAsync(host, busId);
                SetState("Desconectado", Color.Gray);
            }
            finally
            {
                busy = false;
            }
        }

        static bool SameHost(string a, string b) => string.Equals(a?.Trim(), b?.Trim(), StringComparison.OrdinalIgnoreCase);

        /// <summary>Periodic check: shows the state and, if wanted, mounts the wheel again.</summary>
        async Task TickAsync()
        {
            if (cli == null) RefreshCli();
            if (busy || cli == null) return;
            busy = true;
            try
            {
                var host = Host;
                var ports = await cli.PortsAsync();
                var mine = ports.Where(p => SameHost(p.Host, host)).ToList();
                var wanted = deviceBox.SelectedItem as RemoteDevice;
                var mounted = wanted != null ? mine.FirstOrDefault(p => p.BusId == wanted.BusId) : mine.FirstOrDefault();

                if (mounted != null)
                {
                    SetState($"Conectado: {mounted.Product ?? mounted.BusId} (puerto {mounted.Port})", Color.SeaGreen);
                    attachFailureLogged = false;
                    return;
                }
                if (!wantConnected)
                {
                    SetState("Desconectado", Color.Gray);
                    return;
                }
                if (!IsValidHost(host)) return;

                // Not mounted but wanted: on the first try, or on every tick with auto-reconnect.
                if (lastState.StartsWith("Conectado") && !autoReconnectBox.Checked)
                {
                    wantConnected = false;
                    SetState("Desconectado (se perdió la conexión)", Color.DarkOrange);
                    return;
                }
                SetState("Conectando…", Color.DarkOrange);
                var busId = wanted?.BusId;
                if (busId == null)
                {
                    var (devices, listResult) = await cli.ListAsync(host);
                    busId = devices.FirstOrDefault(d => d.IsLogitech)?.BusId;
                    if (busId == null)
                    {
                        if (!attachFailureLogged)
                        {
                            Log(listResult.Ok
                                ? "No hay un volante Logitech disponible en la TV todavía; sigo intentando."
                                : "La TV no responde: " + listResult.Output);
                            attachFailureLogged = true;
                        }
                        if (!autoReconnectBox.Checked) { wantConnected = false; SetState("Desconectado", Color.Gray); }
                        return;
                    }
                }
                var r = await cli.AttachAsync(host, busId, lowLatencyBox.Checked);
                if (!r.Ok && lowLatencyBox.Checked && r.Output.IndexOf("receive-mode", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    Log("Esta versión de usbip-win2 no soporta --receive-mode; desactivo la recepción de baja latencia.");
                    lowLatencyBox.Checked = false;
                    r = await cli.AttachAsync(host, busId, false);
                }
                if (r.Ok)
                {
                    Log($"Montado {busId}: {r.Output}");
                    attachFailureLogged = false;
                    var now = await cli.PortsAsync();
                    var p = now.FirstOrDefault(x => SameHost(x.Host, host) && x.BusId == busId);
                    SetState($"Conectado: {p?.Product ?? busId}" + (p != null ? $" (puerto {p.Port})" : ""), Color.SeaGreen);
                }
                else
                {
                    if (!attachFailureLogged)
                    {
                        Log($"No se pudo montar {busId}: {r.Output}");
                        if (Regex.IsMatch(r.Output, "denied|denegado|privilege|privilegio", RegexOptions.IgnoreCase))
                            Log("Parece un problema de permisos: prueba a abrir el programa como administrador.");
                        else if (autoReconnectBox.Checked)
                            Log("Sigo intentando en segundo plano…");
                        attachFailureLogged = true;
                    }
                    if (!autoReconnectBox.Checked) { wantConnected = false; SetState("Desconectado", Color.Gray); }
                }
            }
            catch (Exception ex)
            {
                Log("Error: " + ex.Message);
            }
            finally
            {
                busy = false;
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                timer.Dispose();
                tray.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
