using System;
using System.IO;
using System.Xml.Serialization;
using Microsoft.Win32;

namespace VolanteUsbIp
{
    /// <summary>User settings, stored in %APPDATA%\VolanteUsbIp\settings.xml.</summary>
    public class Settings
    {
        public string TvHost { get; set; } = "";

        /// <summary>Empty = first Logitech device the TV exports.</summary>
        public string BusId { get; set; } = "";

        public bool ConnectOnStart { get; set; } = true;
        public bool AutoReconnect { get; set; } = true;
        public int CheckIntervalSeconds { get; set; } = 3;
        public bool StartMinimized { get; set; } = false;
        public string UsbIpPath { get; set; } = "";

        static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VolanteUsbIp");
        static string FilePath => Path.Combine(Dir, "settings.xml");

        public static Settings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                    using (var s = File.OpenRead(FilePath))
                        return (Settings)new XmlSerializer(typeof(Settings)).Deserialize(s);
            }
            catch (Exception)
            {
                // Corrupt file: fall back to defaults.
            }
            return new Settings();
        }

        public void Save()
        {
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            using (var s = File.Create(tmp))
                new XmlSerializer(typeof(Settings)).Serialize(s, this);
            if (File.Exists(FilePath)) File.Delete(FilePath);
            File.Move(tmp, FilePath);
        }

        // "Start with Windows" lives in the registry Run key, not in the settings file, so it
        // always reflects reality (the user may remove it from Task Manager).
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunValue = "VolanteUsbIp";

        public static bool StartWithWindows
        {
            get
            {
                using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                    return k?.GetValue(RunValue) != null;
            }
            set
            {
                using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                {
                    if (value) k.SetValue(RunValue, "\"" + System.Windows.Forms.Application.ExecutablePath + "\" --tray");
                    else k.DeleteValue(RunValue, false);
                }
            }
        }
    }
}
