using System;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

namespace VolanteUsbIp
{
    static class Program
    {
        [STAThread]
        static void Main(string[] args)
        {
            // One instance only: a second launch would fight the first over the same wheel.
            using (var mutex = new Mutex(true, @"Local\VolanteUsbIp", out bool first))
            {
                if (!first)
                {
                    MessageBox.Show("Volante USB/IP ya está abierto (revisa la bandeja del sistema).",
                        "Volante USB/IP", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm(startHidden: args.Contains("--tray")));
            }
        }
    }
}
