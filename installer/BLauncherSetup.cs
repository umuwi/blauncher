// BLauncherSetup — установщик лаунчера BLauncher для Windows.
//
// Что делает:
//   1) спрашивает у сервера (https://blauncher.ru/update/manifest_pc.json) актуальную версию сборки;
//   2) качает портативную сборку https://blauncher.ru/downloads/BLauncher_<версия>.zip
//      (если прямой путь пуст — пробует https://blauncher.ru/api/download.php?file=...);
//   3) распаковывает её в выбранную папку (по умолчанию %LOCALAPPDATA%\BLauncher), НЕ удаляя то,
//      чего нет в архиве: launcher_root с игровыми файлами игрока остаётся на месте;
//   4) по тумблерам создаёт ярлык на рабочем столе и в меню «Пуск», прописывает удаление
//      в «Программы и компоненты»;
//   5) по тумблеру запускает BLauncher.exe из папки установки.
//
// Сборка: build-installer.ps1 (csc из .NET Framework 4.x, C# 5 — без интерполяции строк,
// null-условного оператора и прочего синтаксиса новее пятой версии).
//
// Ключи командной строки:
//   /S                тихая установка (без окон), сборка ставится в папку по умолчанию
//   /DIR=<путь>       папка установки
//   /ZIP=<путь>       поставить из локального zip-файла, не обращаясь к серверу
//   /LAUNCH /NOLAUNCH запускать ли лаунчер после установки (в тихом режиме по умолчанию НЕ запускать)
//   /NODESKTOP /NOSTARTMENU   не создавать соответствующий ярлык
//   /UNINSTALL        удалить установленный лаунчер (/REMOVEDATA — вместе с игровыми файлами)
//   /?                справка
//
// Коды возврата: 0 — успех, 1 — ошибка, 2 — отменено пользователем.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("BLauncher Setup")]
[assembly: AssemblyProduct("BLauncher")]
[assembly: AssemblyCompany("blauncher.ru")]
[assembly: AssemblyDescription("Установщик лаунчера BLauncher")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]

namespace BLauncherSetup
{
    // ------------------------------------------------------------------ общие сведения о сборке

    /// <summary>Откуда установщик берёт сборку. Всё по HTTPS — никаких паролей внутри exe.</summary>
    internal static class Site
    {
        public const string Web = "https://blauncher.ru/";
        public const string ManifestUrl = "https://blauncher.ru/update/manifest_pc.json";
        public const string ZipByName = "https://blauncher.ru/downloads/BLauncher_{0}.zip";
        public const string ZipByApi = "https://blauncher.ru/api/download.php?file=BLauncher_{0}.zip";
        /// <summary>
        /// Что НЕ вошло в exe: лаунчер и его библиотеки (BLauncher.exe + app\). Java (runtime\)
        /// наоборот вшита — см. RuntimeResource.
        ///
        /// Так разделено ради репутации файла у SmartScreen и браузеров. Она копится по хешу
        /// установщика, и раньше половины стояли наоборот: в exe лежал лаунчер, который меняется
        /// каждым релизом, а с сервера качалась Java, которая закреплена на 21.0.10 и не меняется
        /// годами. Из-за этого хеш сбрасывался с каждым выпуском, и предупреждение «файл
        /// скачивается редко» получал каждый новый человек, сколько бы их ни было до него.
        /// Теперь в exe то, что не меняется, и хеш живёт от релиза к релизу.
        ///
        /// Побочная выгода: Java больше не качается, а значит не нужен и её хеш, которого сервер
        /// не публиковал — то есть исполняемый код приезжал непроверенным.
        ///
        /// Имя без версии — чтобы в exe не приходилось вшивать номер выпуска: любое вшитое
        /// число меняет байты файла и тем самым обнуляет ту самую репутацию.
        /// </summary>
        public const string LauncherUrl = "https://blauncher.ru/downloads/blauncher-launcher.zip";
        /// <summary>Тот же архив под именем с версией — запасной адрес.</summary>
        public const string LauncherVersionedByName = "https://blauncher.ru/downloads/BLauncher_launcher_{0}.zip";
        /// <summary>Счётчик воронки установки: сколько дошло до конца и на какой стадии бросили
        /// остальные. Протокол и готовые SQL-отчёты — в заголовке самого install.php.</summary>
        public const string StatsUrl = "https://blauncher.ru/api/install.php";
        public const string UserAgent = "BLauncherSetup/1.0 (+https://blauncher.ru/)";

        /// <summary>Версия без ведущей «v» — так называются файлы на сервере (BLauncher_1.12.5.zip).</summary>
        public static string Trim(string version)
        {
            if (string.IsNullOrEmpty(version)) return version;
            return version.TrimStart('v', 'V').Trim();
        }

        public static string ZipUrlFor(string version) { return string.Format(ZipByName, Trim(version)); }
        public static string ZipApiFor(string version) { return string.Format(ZipByApi, Trim(version)); }
        public static string LauncherVersionedUrlFor(string version) { return string.Format(LauncherVersionedByName, Trim(version)); }
    }

    internal static class Log
    {
        private static readonly object Gate = new object();
        public static readonly string Path =
            System.IO.Path.Combine(System.IO.Path.GetTempPath(), "BLauncherSetup.log");

        public static void Write(string message)
        {
            try
            {
                lock (Gate)
                {
                    string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + message + Environment.NewLine;
                    File.AppendAllText(Path, line, new UTF8Encoding(false));
                }
            }
            catch { }
        }
    }

    // ------------------------------------------------------------------ тёмная тема из лаунчера

    internal static class Theme
    {
        public static readonly Color Bg = Hex("#14161B");
        public static readonly Color Surface = Hex("#1B1E25");
        public static readonly Color Surface2 = Hex("#21242C");
        public static readonly Color Line = Hex("#2A2E38");
        public static readonly Color LineStrong = Hex("#3A3E48");
        public static readonly Color Text = Hex("#E9E7E1");
        public static readonly Color Muted = Hex("#A5A9B4");
        public static readonly Color Dim = Hex("#848A99");
        public static readonly Color Accent = Hex("#8B93FF");
        public static readonly Color AccentHover = Hex("#C2C6FF");
        public static readonly Color OnAccent = Hex("#0F1116");
        public static readonly Color Danger = Hex("#E05C5C");
        public static readonly Color Ok = Hex("#5FD6A0");
        public static readonly Color Warn = Hex("#F5A623");

        public static Color Hex(string value) { return ColorTranslator.FromHtml(value); }

        public static Color Mix(Color a, Color b, double t)
        {
            if (t < 0) t = 0; if (t > 1) t = 1;
            return Color.FromArgb(
                (int)Math.Round(a.A + (b.A - a.A) * t),
                (int)Math.Round(a.R + (b.R - a.R) * t),
                (int)Math.Round(a.G + (b.G - a.G) * t),
                (int)Math.Round(a.B + (b.B - a.B) * t));
        }

        public static GraphicsPath Round(Rectangle r, int radius)
        {
            GraphicsPath path = new GraphicsPath();
            if (radius < 1) { path.AddRectangle(r); return path; }
            int d = radius * 2;
            if (d > r.Width) d = r.Width;
            if (d > r.Height) d = r.Height;
            path.AddArc(r.X, r.Y, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        public static Font Font(float size) { return Font(size, FontStyle.Regular); }

        public static Font Font(float size, FontStyle style)
        {
            string[] families = new string[] { "Segoe UI", "Tahoma", "Arial" };
            foreach (string family in families)
            {
                try
                {
                    Font f = new Font(family, size, style);
                    if (string.Equals(f.Name, family, StringComparison.OrdinalIgnoreCase) ||
                        f.Name.IndexOf(family, StringComparison.OrdinalIgnoreCase) >= 0)
                        return f;
                }
                catch { }
            }
            return new Font(FontFamily.GenericSansSerif, size, style);
        }
    }

    // ------------------------------------------------------------------ свои контролы

    internal class RoundedPanel : Panel
    {
        public Color Fill = Theme.Surface;
        public Color BorderColor = Theme.Line;
        public int Radius = 10;

        public RoundedPanel()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Bg;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle r = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = Theme.Round(r, Radius))
            {
                using (SolidBrush brush = new SolidBrush(Fill)) e.Graphics.FillPath(brush, path);
                if (BorderColor.A > 0)
                    using (Pen pen = new Pen(BorderColor)) e.Graphics.DrawPath(pen, path);
            }
            base.OnPaint(e);
        }
    }

    internal enum ButtonKind { Accent, Ghost, Danger }

    internal class RoundedButton : Button
    {
        public ButtonKind Kind = ButtonKind.Ghost;
        public Color SurfaceColor = Theme.Bg;
        public int Radius = 8;

        private bool _hover;
        private bool _down;

        public RoundedButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            UseVisualStyleBackColor = false;
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.75f, FontStyle.Bold);
            Cursor = Cursors.Hand;
            TabStop = true;
        }

        protected override void OnMouseEnter(EventArgs e) { _hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { _hover = false; _down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs mevent) { _down = true; Invalidate(); base.OnMouseDown(mevent); }
        protected override void OnMouseUp(MouseEventArgs mevent) { _down = false; Invalidate(); base.OnMouseUp(mevent); }
        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnPaint(PaintEventArgs pevent)
        {
            Graphics g = pevent.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (SolidBrush bg = new SolidBrush(SurfaceColor)) g.FillRectangle(bg, ClientRectangle);

            Color fill, text, border = Color.Empty;
            if (!Enabled)
            {
                fill = Kind == ButtonKind.Ghost ? SurfaceColor : Theme.Mix(SurfaceColor, Theme.Line, 0.55);
                text = Theme.Dim;
                border = Theme.Line;
            }
            else if (Kind == ButtonKind.Accent)
            {
                fill = _down ? Theme.Mix(Theme.Accent, Color.White, 0.18) : (_hover ? Theme.AccentHover : Theme.Accent);
                text = Theme.OnAccent;
            }
            else if (Kind == ButtonKind.Danger)
            {
                fill = _down ? Theme.Mix(Theme.Danger, Color.Black, 0.2) : (_hover ? Theme.Mix(Theme.Danger, Color.White, 0.18) : Theme.Danger);
                text = Color.White;
            }
            else
            {
                fill = _down ? Theme.Surface2 : (_hover ? Theme.Surface2 : SurfaceColor);
                text = _hover ? Theme.Text : Theme.Muted;
                border = _hover ? Theme.LineStrong : Theme.Line;
            }

            Rectangle r = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = Theme.Round(r, Radius))
            {
                using (SolidBrush brush = new SolidBrush(fill)) g.FillPath(brush, path);
                if (border.A > 0)
                    using (Pen pen = new Pen(border)) g.DrawPath(pen, path);
            }

            Rectangle textRect = new Rectangle(6, 0, Width - 12, Height);
            TextRenderer.DrawText(g, Text, Font, textRect, text,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter |
                TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);

            if (Focused && Enabled && ShowFocusCues)
            {
                Rectangle focus = new Rectangle(3, 3, Width - 7, Height - 7);
                using (GraphicsPath path = Theme.Round(focus, Math.Max(2, Radius - 2)))
                using (Pen pen = new Pen(Theme.Mix(fill, text, 0.5)))
                {
                    pen.DashStyle = DashStyle.Dot;
                    g.DrawPath(pen, path);
                }
            }
        }
    }

    /// <summary>Тумблер: дорожка с бегунком, а справа — подпись. Кликом в любое место переключается.</summary>
    internal class ToggleSwitch : Control
    {
        private bool _checked;
        private double _pos;
        private readonly System.Windows.Forms.Timer _timer;

        public event EventHandler CheckedChanged;

        public ToggleSwitch()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw |
                     ControlStyles.Selectable | ControlStyles.StandardClick, true);
            BackColor = Theme.Surface;
            Font = Theme.Font(10f);
            ForeColor = Theme.Text;
            Height = 28;
            Width = 380;
            TabStop = true;
            Cursor = Cursors.Hand;
            _timer = new System.Windows.Forms.Timer();
            _timer.Interval = 15;
            _timer.Tick += delegate
            {
                double target = _checked ? 1.0 : 0.0;
                double delta = target - _pos;
                if (Math.Abs(delta) < 0.02) { _pos = target; _timer.Stop(); }
                else _pos += delta * 0.35;
                Invalidate();
            };
        }

        public bool Checked
        {
            get { return _checked; }
            set
            {
                if (_checked == value) return;
                _checked = value;
                _pos = _checked ? 1.0 : 0.0;   // без анимации при программной установке
                Invalidate();
                if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty);
            }
        }

        private void Animate()
        {
            if (!_timer.Enabled) _timer.Start();
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            Focus();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (e.Button == MouseButtons.Left && ClientRectangle.Contains(e.Location)) Toggle();
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (e.KeyCode == Keys.Space || e.KeyCode == Keys.Enter) { Toggle(); e.Handled = true; }
        }

        private void Toggle()
        {
            _checked = !_checked;
            Animate();
            if (CheckedChanged != null) CheckedChanged(this, EventArgs.Empty);
        }

        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }
        protected override void OnTextChanged(EventArgs e) { Invalidate(); base.OnTextChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (SolidBrush bg = new SolidBrush(BackColor)) g.FillRectangle(bg, ClientRectangle);

            int trackH = Math.Max(16, Height - 12);
            int trackW = (int)Math.Round(trackH * 1.85);
            int top = (Height - trackH) / 2;

            Color track = Theme.Mix(Theme.LineStrong, Theme.Accent, _pos * (_checked || _pos > 0 ? 1 : 0));
            Color knob = Theme.Mix(Hex("#7E8494"), Color.White, _pos);

            Rectangle trackRect = new Rectangle(0, top, trackW, trackH);
            using (GraphicsPath path = Theme.Round(trackRect, trackH / 2))
            using (SolidBrush brush = new SolidBrush(Enabled ? track : Theme.Line))
                g.FillPath(brush, path);

            int knobD = trackH - 6;
            int knobX = 3 + (int)Math.Round((trackW - 6 - knobD) * _pos);
            Rectangle knobRect = new Rectangle(knobX, top + 3, knobD, knobD);
            using (SolidBrush brush = new SolidBrush(Enabled ? knob : Theme.Dim))
                g.FillEllipse(brush, knobRect);

            Color textColor = Enabled ? (_checked ? Theme.Text : Theme.Muted) : Theme.Dim;
            Rectangle textRect = new Rectangle(trackW + 14, 0, Math.Max(0, Width - trackW - 14), Height);
            TextRenderer.DrawText(g, Text, Font, textRect, textColor,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter |
                TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);

            if (Focused)
            {
                Rectangle focus = textRect;
                focus.Inflate(-2, -4);
                if (focus.Width > 4)
                    ControlPaint.DrawFocusRectangle(g, focus, textColor, BackColor);
            }
        }

        private static Color Hex(string value) { return ColorTranslator.FromHtml(value); }

        protected override void Dispose(bool disposing)
        {
            if (disposing) _timer.Dispose();
            base.Dispose(disposing);
        }
    }

    /// <summary>Полоса прогресса: дорожка со скруглением и заливка акцентом.</summary>
    internal class ProgressStrip : Control
    {
        private double _value;

        public ProgressStrip()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Bg;
            Height = 6;
        }

        /// <summary>0..1, отрицательное значение — «неизвестно» (бегущая полоса).</summary>
        public double Value
        {
            get { return _value; }
            set
            {
                double v = value;
                if (v < 0) v = -1;
                if (v > 1) v = 1;
                if (Math.Abs(v - _value) < 0.0005) return;
                _value = v;
                Invalidate();
            }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (SolidBrush bg = new SolidBrush(BackColor)) g.FillRectangle(bg, ClientRectangle);

            Rectangle track = new Rectangle(0, 0, Width - 1, Height - 1);
            using (GraphicsPath path = Theme.Round(track, Height / 2))
            using (SolidBrush brush = new SolidBrush(Theme.Surface2))
                g.FillPath(brush, path);

            if (_value <= 0) return;
            int fillW = (int)Math.Round((Width - 1) * _value);
            if (fillW < Height) fillW = Height;
            Rectangle fill = new Rectangle(0, 0, fillW, Height - 1);
            using (GraphicsPath path = Theme.Round(fill, Height / 2))
            using (LinearGradientBrush brush = new LinearGradientBrush(
                       new Rectangle(0, 0, Math.Max(2, fillW), Math.Max(2, Height)), Theme.Accent, Theme.AccentHover, 0f))
                g.FillPath(brush, path);
        }
    }

    // ------------------------------------------------------------------ модель

    internal sealed class InstallJob
    {
        public string Dir = "";
        public string ZipPath;          // null — качать с сервера
        public string ZipUrl = "";      // адрес, который подтвердил сервер (может быть пустым)
        public string ZipSha256 = "";   // из manifest_pc.json, если он там есть
        public string LauncherUrl = "";      // архив без runtime\ — то, что качается
        public string LauncherSha256 = "";   // sha256Launcher из манифеста
        public long LauncherSize;
        public long ZipSize;            // размер полной сборки — запасной путь
        public string Version = "";
        public bool Desktop = true;
        public bool StartMenu = true;
        public bool Launch = true;
        public bool DesktopCreated;     // что реально получилось, а не что просили
        public bool StartMenuCreated;
    }

    internal sealed class RemoteInfo
    {
        public string Version = "";
        public string Changelog = "";
        public string ZipUrl = "";
        public string ZipSha256 = "";
        public string LauncherUrl = "";
        public string LauncherSha256 = "";
        public long ZipSize;
        public long LauncherSize;
        public bool FromServer;
    }

    internal sealed class ProgressSink
    {
        public Action<string> Status;
        public Action<double> Fraction;
        public Func<bool> Cancelled;

        public void Say(string text) { if (Status != null) Status(text); }
        public void At(double fraction) { if (Fraction != null) Fraction(fraction); }
        public bool IsCancelled { get { return Cancelled != null && Cancelled(); } }

        public void Check()
        {
            if (IsCancelled) throw new OperationCanceledException("Отменено пользователем");
        }
    }

    // ------------------------------------------------------------------ работа с сетью и архивами

    internal static class Net
    {
        public static void InitTls()
        {
            try
            {
                // Только TLS 1.2 (3072). Раньше сюда же добавлялись 1.1 (768) и 1.0
                // (SecurityProtocolType.Tls) — сервер их не требует, а их наличие оставляло
                // возможность согласовать устаревшее соединение. TLS 1.3 не указываем: в
                // .NET Framework 4.x его поддержка появляется не везде, и попытка выставить
                // неподдерживаемое значение бросает NotSupportedException.
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
                ServicePointManager.Expect100Continue = false;
                ServicePointManager.DefaultConnectionLimit = 8;
            }
            catch (Exception ex) { Log.Write("tls: " + ex.Message); }
        }

        private static HttpWebRequest Request(string url, int timeoutMs, bool decompress)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = Site.UserAgent;
            req.Timeout = timeoutMs;
            req.ReadWriteTimeout = Math.Max(timeoutMs, 60000);
            req.AllowAutoRedirect = true;
            // Для архива сжатие не включаем: Content-Length тогда относится к сжатому телу, и сверка
            // «скачано == Content-Length» ложно ругалась бы на корректный файл.
            req.AutomaticDecompression = decompress
                ? DecompressionMethods.GZip | DecompressionMethods.Deflate
                : DecompressionMethods.None;
            return req;
        }

        public static string GetText(string url, int timeoutMs)
        {
            HttpWebRequest req = Request(url, timeoutMs, true);
            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            using (Stream stream = resp.GetResponseStream())
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
                return reader.ReadToEnd();
        }

        public static long HeadLength(string url, int timeoutMs)
        {
            try
            {
                HttpWebRequest req = Request(url, timeoutMs, true);
                req.Method = "HEAD";
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                    return resp.ContentLength;
            }
            catch (Exception ex)
            {
                Log.Write("head " + url + ": " + ex.Message);
                return 0;
            }
        }

        /// <summary>Скачивает файл, отдавая наружу «сколько уже / сколько всего».</summary>
        public static long Download(string url, string target, Action<long, long> progress, ProgressSink sink)
        {
            return Download(url, target, progress, sink, true);
        }

        /// <summary>
        /// Скачивает файл. Если файл уже частично лежит на диске и сервер умеет Range, загрузка
        /// продолжается с того места, где оборвалась, а не начинается заново.
        /// useProxy=false — идти напрямую, минуя системный прокси (он бывает медленным и рвёт
        /// длинные загрузки; так делает и curl, который читает только переменные окружения).
        /// </summary>
        public static long Download(string url, string target, Action<long, long> progress, ProgressSink sink, bool useProxy)
        {
            long existing = 0;
            try { if (File.Exists(target)) existing = new FileInfo(target).Length; } catch { }

            HttpWebRequest req = Request(url, 45000, false);
            if (!useProxy) req.Proxy = null;
            if (existing > 0) req.AddRange(existing);

            using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
            {
                bool resumed = existing > 0 && resp.StatusCode == HttpStatusCode.PartialContent;
                if (!resumed) existing = 0;    // сервер не поддержал докачку — пишем файл заново

                long total = resp.ContentLength >= 0 ? existing + resp.ContentLength : 0;
                string dir = Path.GetDirectoryName(target);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir)) Directory.CreateDirectory(dir);
                if (total > 0 && existing >= total) existing = 0;   // мусор от прошлой попытки

                using (Stream source = resp.GetResponseStream())
                using (FileStream file = new FileStream(target,
                           resumed && existing > 0 ? FileMode.Append : FileMode.Create,
                           FileAccess.Write, FileShare.None, 1 << 16))
                {
                    byte[] buffer = new byte[1 << 16];
                    long received = existing;
                    int read;
                    while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        sink.Check();
                        file.Write(buffer, 0, read);
                        received += read;
                        if (progress != null) progress(received, total);
                    }
                    file.Flush();
                    if (total > 0 && received != total)
                        throw new IOException(string.Format("Файл скачан не полностью: {0} из {1} байт", received, total));
                    return received;
                }
            }
        }
    }

    // ------------------------------------------------------------------ счётчик воронки установки

    /// <summary>Состояние одного запуска установщика для <see cref="Stats"/>.</summary>
    internal sealed class StatsRun
    {
        public string Id;
        public string Stage = "start";
        /// <summary>Операция внутри стадии — попадает в detail рядом с именем исключения.</summary>
        public string Step;
        public readonly Stopwatch Clock = Stopwatch.StartNew();
        public bool Enabled;
    }

    /// <summary>
    ///  Сообщает серверу, что установка началась, через какие стадии прошла и чем кончилась —
    ///  чтобы в отчётах было видно, сколько людей доходит до конца и на какой стадии бросают
    ///  остальные. Брошенная установка просто перестаёт присылать события: на сервере у неё
    ///  остаётся outcome = NULL и последняя достигнутая стадия.
    ///
    ///  <para>Правило номер один: это не должно мешать установке. Любая ошибка глотается и
    ///  попадает только в локальный лог, таймаут короткий, а события начала и стадий уходят
    ///  в фоновом потоке, чтобы не задерживать распаковку ни на миллисекунду. Отключается
    ///  ключом /NOSTATS.</para>
    ///
    ///  <para>Не отправляется ничего, что указывает на человека: случайный id одного запуска,
    ///  версия сборки, версия Windows, разрядность, стадия и длительность. У исключений берётся
    ///  только имя класса — в тексте сообщения бывают пути с именем пользователя.</para>
    /// </summary>
    internal static class Stats
    {
        private const int TimeoutMs = 2500;
        // Хватает на две попытки отправки итога (см. SenderLoop) плюс немного сверху. Раньше
        // стояло 3000, то есть вторая попытка не успевала бы в принципе.
        private const int FlushMs = 6000;
        private static bool _disabled;

        // Порядок событий обязателен: на сервере «start» вставляет строку, а всё остальное её
        // обновляет, поэтому событие, обогнавшее start, не найдёт строки и потеряется — а если
        // start придёт последним, успешная установка останется помеченной как брошенная.
        // Поэтому отправка одна и последовательная: вызывающий только кладёт событие в очередь.
        private static readonly object Gate = new object();
        private static readonly Queue<string> Pending = new Queue<string>();
        private static Thread _sender;
        private static int _inFlight;
        // Сеть недоступна — отметки о стадиях дальше не посылаем: иначе каждое из шести событий
        // ждало бы свой таймаут, и конец установки повис бы на пятнадцать секунд вместо двух с
        // половиной.
        //
        // Но ИТОГОВОЕ событие (done/fail/cancel) отправляется всё равно, даже после сбоя. Раньше
        // первая же неудача ставила общий флаг и чистила очередь навсегда, поэтому одна заминка
        // сети посреди двадцатисекундной загрузки убивала остаток воронки. В данных это видно:
        // установка 2d30966c прислала start, java_extract и launcher_download, скачала архив
        // целиком (в логе nginx GET на все 37 646 307 байт) — и замолчала, оставшись «брошенной»,
        // хотя почти наверняка доустановилась.
        private static bool _stagesOff;

        /// <summary>Ключ /NOSTATS: после этого ни одно событие не уходит.</summary>
        public static void Disable()
        {
            _disabled = true;
            Log.Write("stats: выключено ключом /NOSTATS");
        }

        /// <summary>Событие «начал». Возвращает состояние запуска — его носит с собой Install.</summary>
        public static StatsRun Begin(string version)
        {
            StatsRun run = new StatsRun();
            if (_disabled || Site.StatsUrl.Contains("CHANGE-ME")) return run;
            try
            {
                run.Id = NewId();
                run.Enabled = true;
                Enqueue("{\"action\":\"start\",\"id\":\"" + run.Id + "\",\"v\":\"" + Esc(version)
                        + "\",\"os\":\"" + Esc(OsVersion()) + "\",\"arch\":\"" + Esc(Arch()) + "\"}");
            }
            catch (Exception ex) { run.Enabled = false; Log.Write("stats begin: " + ex.Message); }
            return run;
        }

        /// <summary>Отметка о переходе к следующей стадии — по ней и видно, где бросают.</summary>
        public static void Mark(StatsRun run, string stage)
        {
            if (run == null || !run.Enabled) return;
            run.Stage = stage;
            Enqueue("{\"action\":\"stage\",\"id\":\"" + run.Id + "\",\"stage\":\"" + Esc(stage) + "\"}");
        }

        /// <summary>Событие «дошёл до конца».</summary>
        public static void Done(StatsRun run)
        {
            if (run == null || !run.Enabled) return;
            Enqueue("{\"action\":\"done\",\"id\":\"" + run.Id + "\",\"ms\":" + Ms(run) + "}", true);
            // Процесс может завершиться сразу после Install (тихий режим), поэтому здесь
            // единственное место, где мы действительно ждём — и то не дольше FlushMs.
            Flush();
        }

        /// <summary>Событие «сорвалось»: отмена пользователем либо ошибка.</summary>
        public static void Failed(StatsRun run, bool cancelled, Exception error)
        {
            if (run == null || !run.Enabled) return;
            string detail = error == null ? "" : error.GetType().Name;
            if (error != null && error.Data["bl_tag"] is string) detail += ":" + (string)error.Data["bl_tag"];
            if (!string.IsNullOrEmpty(run.Step)) detail += ":" + run.Step;
            // Исходная причина. Распаковщик заворачивает любую ошибку записи в IOException, и 7 октября
            // в отчёте стояло просто «IOException» — ни антивирус (UnauthorizedAccess), ни занятый
            // файл (код Windows 32), ни полный диск (112) было не отличить. Добавляем тип исходного
            // исключения и код Windows — это просто имя и число, ничего о человеке в них нет.
            Exception root = error;
            while (root != null && root.InnerException != null) root = root.InnerException;
            if (root != null && root != error)
                detail += ":" + root.GetType().Name;
            if (root != null)
            {
                int hr = System.Runtime.InteropServices.Marshal.GetHRForException(root);
                if ((hr & 0xFFFF0000) == 0x80070000)
                    detail += ":w" + (hr & 0xFFFF).ToString(CultureInfo.InvariantCulture);
            }
            // Файл из архива, на котором встала распаковка (Zip.ExtractArchive кладёт его в Data).
            // Только безопасные символы и не длиннее 60 — это путь внутри runtime\, не данные человека.
            // Разделители пути — пробелом: api/install.php вырезает из detail всё, кроме [\w .:-],
            // и «runtime/bin/jvm.dll» превратился бы в нечитаемое «runtimebinjvm.dll». Лимит detail
            // на сервере 120 символов; если путь длинный, оставляем хвост — в нём имя файла.
            string entry = error == null ? null : error.Data["entry"] as string;
            if (!string.IsNullOrEmpty(entry))
            {
                StringBuilder safe = new StringBuilder();
                foreach (char c in entry)
                {
                    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '.' || c == '-' || c == '_')
                        safe.Append(c);
                    else if (c == '/' || c == '\\')
                        safe.Append(' ');
                }
                string text = safe.ToString().Trim();
                if (text.Length > 60) text = text.Substring(text.Length - 60);
                if (text.Length > 0) detail += ":" + text;
            }
            Enqueue("{\"action\":\"" + (cancelled ? "cancel" : "fail") + "\",\"id\":\"" + run.Id
                    + "\",\"stage\":\"" + Esc(run.Stage) + "\",\"detail\":\"" + Esc(detail)
                    + "\",\"ms\":" + Ms(run) + "}", true);
            Flush();
        }

        // ---------- внутреннее ----------

        private static long Ms(StatsRun run)
        {
            long ms = run.Clock.ElapsedMilliseconds;
            return ms < 0 ? 0 : ms;
        }

        /// <summary>Кладёт событие в очередь и возвращается немедленно — установку не задерживает.</summary>
        private static void Enqueue(string json) { Enqueue(json, false); }

        /// <param name="terminal">
        ///  Итог установки. Такие события отправляются даже после сетевого сбоя и получают вторую
        ///  попытку: без итога строка на сервере навсегда остаётся «брошенной».
        /// </param>
        private static void Enqueue(string json, bool terminal)
        {
            try
            {
                lock (Gate)
                {
                    if (_stagesOff && !terminal) return;
                    if (terminal)
                    {
                        // Итог известен — отметки о стадиях больше ни к чему, и ждать их отправки
                        // нельзя: при недоступной сети каждая съедает свой таймаут, и вторая
                        // попытка отправить итог не успевает уложиться в FlushMs. Выбрасываем.
                        // Стадию, на которой всё кончилось, итоговое событие несёт в себе само.
                        Queue<string> keep = new Queue<string>();
                        while (Pending.Count > 0)
                        {
                            string left = Pending.Dequeue();
                            if (IsTerminal(left)) keep.Enqueue(left);
                        }
                        while (keep.Count > 0) Pending.Enqueue(keep.Dequeue());
                    }
                    Pending.Enqueue(json);
                    if (_sender == null)
                    {
                        _sender = new Thread(SenderLoop);
                        _sender.IsBackground = true;   // не задерживает выход процесса
                        _sender.Name = "stats";
                        _sender.Start();
                    }
                    Monitor.PulseAll(Gate);
                }
            }
            catch (Exception ex) { Log.Write("stats enqueue: " + ex.Message); }
        }

        /// <summary>Единственный отправляющий поток: строго по одному событию, в порядке очереди.</summary>
        private static void SenderLoop()
        {
            while (true)
            {
                string json;
                lock (Gate)
                {
                    while (Pending.Count == 0) Monitor.Wait(Gate);
                    json = Pending.Dequeue();
                    _inFlight++;
                }
                bool terminal = IsTerminal(json);
                bool ok = Send(json);
                if (!ok && terminal)
                {
                    // Итог — единственное, за что стоит подождать ещё раз: без него установка
                    // навсегда останется в отчёте незавершённой.
                    Log.Write("stats: итог не ушёл, пробую второй раз");
                    ok = Send(json);
                }
                lock (Gate)
                {
                    _inFlight--;
                    if (!ok)
                    {
                        // Сети нет — отметки о стадиях больше не посылаем и выбрасываем те, что
                        // ещё в очереди. Итоговые события остаются: они пройдут мимо _stagesOff.
                        _stagesOff = true;
                        Queue<string> keep = new Queue<string>();
                        while (Pending.Count > 0)
                        {
                            string left = Pending.Dequeue();
                            if (IsTerminal(left)) keep.Enqueue(left);
                        }
                        while (keep.Count > 0) Pending.Enqueue(keep.Dequeue());
                    }
                    Monitor.PulseAll(Gate);
                }
            }
        }

        /// <summary>Итоговое ли это событие — по действию внутри json.</summary>
        private static bool IsTerminal(string json)
        {
            if (string.IsNullOrEmpty(json)) return false;
            return json.IndexOf("\"action\":\"done\"", StringComparison.Ordinal) >= 0
                || json.IndexOf("\"action\":\"fail\"", StringComparison.Ordinal) >= 0
                || json.IndexOf("\"action\":\"cancel\"", StringComparison.Ordinal) >= 0;
        }

        /// <summary>
        ///  Ждёт, пока очередь опустеет, но не дольше FlushMs.
        ///
        ///  <para>Чего этот бюджет НЕ покрывает: случай, когда хост статистики вообще не
        ///  разрешается в DNS. Разрешение имени идёт до того, как начинает действовать
        ///  req.Timeout, и одна такая попытка съедает все шесть секунд — итоговое событие
        ///  тогда не уходит вовсе. Проверено на недостижимом хосте: установка завершилась за
        ///  7,3 с и в лог попало только «не успел отправить».</para>
        ///
        ///  <para>Так и оставлено сознательно. Удлинять ожидание значит задерживать человека в
        ///  конце установки ради строки в отчёте, а это не та цена. Случай, который правка
        ///  действительно закрывает, — обычный обрыв посреди установки: там DNS уже разрешён,
        ///  отправка срывается быстро, и итог успевает уйти со второй попытки.</para>
        /// </summary>
        private static void Flush()
        {
            try
            {
                DateTime deadline = DateTime.UtcNow.AddMilliseconds(FlushMs);
                lock (Gate)
                {
                    while (Pending.Count > 0 || _inFlight > 0)
                    {
                        int left = (int)(deadline - DateTime.UtcNow).TotalMilliseconds;
                        if (left <= 0)
                        {
                            Log.Write("stats: не успел отправить за " + FlushMs + " мс, бросаю очередь");
                            return;
                        }
                        Monitor.Wait(Gate, left);
                    }
                }
            }
            catch (Exception ex) { Log.Write("stats flush: " + ex.Message); }
        }

        /// <summary>Возвращает false, если отправить не удалось.</summary>
        private static bool Send(string json)
        {
            try
            {
                byte[] body = Encoding.UTF8.GetBytes(json);
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(Site.StatsUrl);
                req.Method = "POST";
                req.ContentType = "application/json; charset=utf-8";
                req.UserAgent = Site.UserAgent;
                req.Timeout = TimeoutMs;
                req.ReadWriteTimeout = TimeoutMs;
                req.ContentLength = body.Length;
                using (Stream stream = req.GetRequestStream()) stream.Write(body, 0, body.Length);
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                    if ((int)resp.StatusCode != 200) Log.Write("stats: HTTP " + (int)resp.StatusCode);
                }
                return true;
            }
            catch (Exception ex)
            {
                // Сервер недоступен, нет интернета, ответил ошибкой — установке это безразлично.
                Log.Write("stats: " + ex.Message);
                return false;
            }
        }

        private static string NewId()
        {
            byte[] bytes = new byte[16];
            using (RNGCryptoServiceProvider rng = new RNGCryptoServiceProvider()) rng.GetBytes(bytes);
            StringBuilder hex = new StringBuilder(32);
            foreach (byte b in bytes) hex.Append(b.ToString("x2", CultureInfo.InvariantCulture));
            return hex.ToString();
        }

        private static string OsVersion()
        {
            try { return Environment.OSVersion.Version.ToString(); } catch { return ""; }
        }

        private static string Arch()
        {
            try
            {
                string env = Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE");
                if (!string.IsNullOrEmpty(env) && env.ToUpperInvariant() == "ARM64") return "arm64";
                return Environment.Is64BitOperatingSystem ? "x64" : "x86";
            }
            catch { return ""; }
        }

        /// <summary>Все поля и так ограничены безопасным набором символов — это страховка,
        /// чтобы случайная кавычка в будущем значении не сломала JSON.</summary>
        private static string Esc(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            StringBuilder sb = new StringBuilder(value.Length);
            foreach (char c in value)
            {
                if (c == '"' || c == '\\') sb.Append('\\').Append(c);
                else if (c >= ' ') sb.Append(c);
            }
            return sb.ToString();
        }
    }

    internal static class Zip
    {
        /// <summary>
        /// Проверяет архив и возвращает суммарный размер распакованных файлов.
        /// needLauncher — в корне должен быть BLauncher.exe;
        /// needRuntime — должна быть папка runtime\ (есть только в полной сборке; в архиве
        /// лаунчера её нет нарочно, потому что Java вшита в сам установщик).
        /// </summary>
        public static long Inspect(string zipPath, bool needLauncher, bool needRuntime)
        {
            using (FileStream file = File.OpenRead(zipPath))
            using (ZipArchive archive = new ZipArchive(file, ZipArchiveMode.Read))
            {
                long total = 0;
                bool hasLauncher = false;
                bool hasRuntime = false;
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    if (string.IsNullOrEmpty(entry.Name)) continue;   // запись каталога
                    total += entry.Length;
                    string name = entry.FullName.Replace('\\', '/');
                    if (string.Equals(name, "BLauncher.exe", StringComparison.OrdinalIgnoreCase)) hasLauncher = true;
                    if (name.StartsWith("runtime/", StringComparison.OrdinalIgnoreCase)) hasRuntime = true;
                }
                if (needLauncher && !hasLauncher)
                    throw Bad("no_exe",
                        "В архиве нет BLauncher.exe в корне — это не сборка лаунчера. Выберите архив " +
                        "BLauncher_<версия>.zip с сайта blauncher.ru.");
                if (needRuntime && !hasRuntime)
                    throw Bad("no_runtime", "В архиве нет папки runtime — это не полная сборка.");
                if (total <= 0)
                    throw Bad("empty", "Архив пуст.");
                return total;
            }
        }

        /// <summary>
        /// Отказ в архиве с короткой меткой причины для статистики (Stats.Failed допишет её к
        /// «InvalidDataException»). 20.10.2026 у 3cdff403 три отказа подряд пришли одним именем
        /// класса, а причин у него пять — какая сработала, понять было нельзя.
        /// </summary>
        public static InvalidDataException Bad(string tag, string message)
        {
            InvalidDataException ex = new InvalidDataException(message);
            ex.Data["bl_tag"] = tag;
            return ex;
        }

        /// <summary>Есть ли в архиве папка runtime\ (своя Java).</summary>
        public static bool HasRuntime(string zipPath)
        {
            using (FileStream file = File.OpenRead(zipPath))
            using (ZipArchive archive = new ZipArchive(file, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    if (entry.FullName.Replace('\\', '/').StartsWith("runtime/", StringComparison.OrdinalIgnoreCase)
                        && !string.IsNullOrEmpty(entry.Name)) return true;
                }
            }
            return false;
        }

        /// <summary>Суммарный размер распакованных файлов архива.</summary>
        public static long ArchiveSize(ZipArchive archive)
        {
            long total = 0;
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue;
                total += entry.Length;
            }
            return total;
        }

        /// <summary>Распаковывает архив поверх папки: файлы из архива перезаписываются, чужие — остаются.</summary>
        public static void Extract(string zipPath, string targetDir, ProgressSink sink, double from, double to, long totalBytes)
        {
            using (FileStream file = File.OpenRead(zipPath))
            using (ZipArchive archive = new ZipArchive(file, ZipArchiveMode.Read))
                ExtractArchive(archive, targetDir, sink, from, to, totalBytes);
        }

        /// <summary>
        /// Файл встроенной Java, который Windows 10/11 уже держит в System32, — его не распаковываем.
        ///
        /// 11 и 13 октября две чистые установки на разных ПК (Windows 10 и 11) упали на одном и том же
        /// runtime\bin\ucrtbase.dll: UnauthorizedAccess (код 5) всю минуту ожидания, хотя java.dll и
        /// ещё 80 dll перед ним записались, а лаунчер на этих ПК ни разу не запускался. Значит, файл
        /// не занят — запись запрещает защита (имя системной библиотеки в папке пользователя похоже
        /// на подмену dll). Своя копия Java и не нужна: без неё Java 21.0.10 грузит
        /// C:\Windows\System32\ucrtbase.dll — проверено 13.10.2026 (java, zip, http, awt, шрифты).
        /// </summary>
        private static bool SystemHasOwnCopy(string entryName)
        {
            string name = entryName.Replace('/', '\\');
            if (!name.Equals(@"runtime\bin\ucrtbase.dll", StringComparison.OrdinalIgnoreCase)) return false;
            try { return File.Exists(Path.Combine(Environment.SystemDirectory, "ucrtbase.dll")); }
            catch { return false; }
        }

        /// <summary>То же для архива, открытого не с диска (например, вшитого в exe).</summary>
        public static void ExtractArchive(ZipArchive archive, string targetDir, ProgressSink sink, double from, double to, long totalBytes)
        {
            string root = Path.GetFullPath(targetDir);
            if (!root.EndsWith(Path.DirectorySeparatorChar.ToString()))
                root += Path.DirectorySeparatorChar;

            long done = 0;
            byte[] buffer = new byte[1 << 16];
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                if (sink != null) sink.Check();
                if (string.IsNullOrEmpty(entry.Name)) continue;

                string relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                string destination;
                try { destination = Path.GetFullPath(Path.Combine(targetDir, relative)); }
                catch (Exception ex) { Log.Write("zip: пропускаю " + entry.FullName + " — " + ex.Message); continue; }

                // Запись, которая после разворота оказалась вне папки установки, не распаковываем.
                if (!destination.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                {
                    Log.Write("zip: пропускаю запись вне папки установки " + entry.FullName);
                    continue;
                }

                string folder = Path.GetDirectoryName(destination);
                if (!string.IsNullOrEmpty(folder) && !Directory.Exists(folder)) Directory.CreateDirectory(folder);

                // Имя файла совпало с уже существующим КАТАЛОГОМ: FileMode.Create на каталоге
                // даёт UnauthorizedAccessException. Пропускаем с записью в лог, а не валим всю
                // установку из-за одной странной записи в архиве.
                if (Directory.Exists(destination))
                {
                    Log.Write("zip: пропускаю " + entry.FullName + " — по этому пути уже каталог");
                    continue;
                }

                if (SystemHasOwnCopy(entry.FullName))
                {
                    Log.Write("zip: пропускаю " + entry.FullName + " — в Windows есть своя копия");
                    done += entry.Length;
                    continue;
                }

                if (File.Exists(destination))
                {
                    // Иначе перезапись упадёт на файле с атрибутом «только чтение».
                    try { File.SetAttributes(destination, FileAttributes.Normal); } catch { }
                }

                // Запись с повторами.
                //
                // Так выглядел реальный сбой у пользователя (телеметрия установки, 3 октября):
                // стадия runtime_extract, UnauthorizedAccessException, 534 секунды впустую — у
                // человека рвался канал, архив скачался только с шестой попытки, а распаковка
                // упала на первом же недоступном файле. Антивирусы блокируют только что
                // записанные .exe и .dll на доли секунды, а в runtimein их десятки, так что
                // попасть в такую блокировку легко. Одной повторной попытки обычно достаточно;
                // без них девятиминутная загрузка уходила в никуда.
                //
                // 7 октября двум новым игрокам (Windows 10, чистая установка) этого не хватило:
                // java_extract шла ~34 секунды вместо обычных пяти — антивирус проверял каждый
                // файл — и упала с IOException. Четыре попытки давали в сумме 1,8 секунды, а
                // проверка большого файла (lib\modules, jvm.dll) идёт дольше. Теперь ждём до ~20 с.
                //
                // 8 октября и 20 секунд не хватило: java_extract упала с UnauthorizedAccess (код 5)
                // через 55 с после начала, а тот же ПК через три минуты поставил всё без ошибок.
                // Поэтому ждём по ВРЕМЕНИ — до минуты на файл, а не фиксированное число попыток, и
                // перед повтором убираем недописанный файл: если антивирус успел поставить
                // блокировку на полупустой файл, свежий файл с тем же именем он проверит заново.
                long doneBeforeEntry = done;
                const long maxWaitMs = 60000;
                System.Diagnostics.Stopwatch waited = null;
                for (int attempt = 1; ; attempt++)
                {
                    try
                    {
                        done = doneBeforeEntry;   // при повторе прогресс считаем заново
                        using (Stream source = entry.Open())
                        using (FileStream output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                        {
                            int read;
                            while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                output.Write(buffer, 0, read);
                                done += read;
                                if (totalBytes > 0 && sink != null)
                                    sink.At(from + (to - from) * ((double)done / totalBytes));
                            }
                        }
                        break;
                    }
                    catch (Exception ex)
                    {
                        bool retryable = ex is UnauthorizedAccessException || ex is IOException;
                        if (waited == null) waited = System.Diagnostics.Stopwatch.StartNew();
                        if (!retryable || waited.ElapsedMilliseconds >= maxWaitMs)
                        {
                            // Называем ФАЙЛ. Прежнее сообщение было просто текстом исключения,
                            // по которому нельзя понять, что именно заблокировано.
                            IOException failure = new IOException(
                                "Не удалось записать " + entry.FullName + " (" + ex.GetType().Name + ")."
                                + (ex is UnauthorizedAccessException
                                    ? " Чаще всего это антивирус: добавьте папку установки в исключения"
                                      + " и повторите. Ещё причины — нет прав на запись в эту папку или"
                                      + " лаунчер уже запущен из неё."
                                    : " Файл занят другой программой — чаще всего антивирусом. Добавьте папку"
                                      + " установки в исключения антивируса или выберите другую папку,"
                                      + " проверьте свободное место на диске и повторите."),
                                ex);
                            // Для телеметрии: 8 октября отчёт сказал «UnauthorizedAccess», но не сказал,
                            // на каком файле. Путь внутри архива — это runtime\bin\..., ничего о человеке.
                            failure.Data["entry"] = entry.FullName;
                            throw failure;
                        }
                        Log.Write("zip: " + entry.FullName + " занят (" + ex.GetType().Name
                                  + "), попытка " + attempt + ", ждём " + waited.ElapsedMilliseconds / 1000 + " с");
                        if (sink != null && attempt == 2)
                            sink.Say("Файл " + entry.Name + " занят — скорее всего, его проверяет антивирус. Жду до минуты…");
                        if (ex is UnauthorizedAccessException)
                        {
                            try
                            {
                                if (File.Exists(destination))
                                {
                                    File.SetAttributes(destination, FileAttributes.Normal);
                                    File.Delete(destination);
                                }
                            }
                            catch { }   // пока файл держат, удалить его тоже нельзя — просто ждём дальше
                        }
                        System.Threading.Thread.Sleep(Math.Min(500 * attempt, 3000));
                        if (sink != null) sink.Check();   // дать отменить установку во время ожидания
                    }
                }
                try { File.SetLastWriteTime(destination, entry.LastWriteTime.DateTime); } catch { }
            }
        }
    }

    // ------------------------------------------------------------------ собственно установка

    internal static class Installer
    {
        public static RemoteInfo Probe()
        {
            RemoteInfo info = new RemoteInfo();
            info.Version = BuildInfo.DefaultVersion;
            info.ZipUrl = Site.ZipUrlFor(info.Version);

            try
            {
                string json = Net.GetText(Site.ManifestUrl, 15000);
                string version = JsonValue(json, "latestVersion");
                if (!string.IsNullOrEmpty(version) && Regex.IsMatch(version, "^[0-9A-Za-z._-]{1,32}$"))
                    info.Version = version;
                else if (!string.IsNullOrEmpty(version))
                    Log.Write("probe: версия с сервера выглядит странно (" + version + ") — беру вшитую");

                info.Changelog = JsonValue(json, "changelog");
                info.ZipSha256 = JsonValue(json, "sha256Zip");
                info.LauncherSha256 = JsonValue(json, "sha256Launcher");
                info.FromServer = true;

                info.ZipUrl = Site.ZipUrlFor(info.Version);
                info.ZipSize = Net.HeadLength(info.ZipUrl, 15000);
                if (info.ZipSize <= 0)
                {
                    string fallback = Site.ZipApiFor(info.Version);
                    long size = Net.HeadLength(fallback, 15000);
                    if (size > 0) { info.ZipUrl = fallback; info.ZipSize = size; }
                }

                // То, что не влезло в exe (лаунчер и библиотеки) — лежит как
                // blauncher-launcher.zip; если его нет, пробуем то же под именем с версией,
                // и лишь потом полную сборку (она тоже содержит лаунчер, просто тяжелее).
                info.LauncherUrl = Site.LauncherUrl;
                info.LauncherSize = Net.HeadLength(info.LauncherUrl, 15000);
                if (info.LauncherSize <= 0)
                {
                    info.LauncherUrl = Site.LauncherVersionedUrlFor(info.Version);
                    info.LauncherSize = Net.HeadLength(info.LauncherUrl, 15000);
                }
                if (info.LauncherSize <= 0) info.LauncherUrl = "";

                Log.Write(string.Format("probe: сервер отдал версию {0}, лаунчер {1}, sha256Launcher {2}, полная сборка {3} ({4} байт), sha256Zip {5}",
                    info.Version,
                    info.LauncherSize > 0 ? info.LauncherUrl + " (" + info.LauncherSize + " байт)" : "нет отдельного архива",
                    string.IsNullOrEmpty(info.LauncherSha256) ? "не опубликован" : info.LauncherSha256,
                    info.ZipUrl, info.ZipSize,
                    string.IsNullOrEmpty(info.ZipSha256) ? "не опубликован" : info.ZipSha256));
            }
            catch (Exception ex)
            {
                Log.Write("probe: сервер недоступен — " + ex.Message);
                info.FromServer = false;
            }
            return info;
        }

        private static string JsonValue(string json, string key)
        {
            if (string.IsNullOrEmpty(json)) return "";
            Match m = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            if (!m.Success) return "";
            string value = m.Groups[1].Value;
            value = value.Replace("\\n", "\n").Replace("\\r", "").Replace("\\\"", "\"").Replace("\\/", "/");
            try { value = Regex.Unescape(value); } catch { }
            return value;
        }

        public static string DefaultDir()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BLauncher");
        }

        public static string GuiExe(string dir) { return Path.Combine(dir, "BLauncher.exe"); }

        /// <summary>Возвращает текст проблемы с папкой установки или null, если папка годится.</summary>
        public static string ValidateTargetDir(string dir)
        {
            string shape = ValidateDirShape(dir);
            if (shape != null) return shape;

            string resolved;
            try { resolved = Path.GetFullPath(dir.Trim()); } catch { return "Такой путь нельзя использовать."; }

            // Непустая чужая папка. Удаление лаунчера снесёт из неё всё, поэтому ставить можно
            // только в пустую папку либо в свою же прежнюю установку (её признак — install.json).
            if (!IsEmptyOrMissing(resolved) && !LooksLikeInstall(resolved))
                return "Папка не пуста, и это не прежняя установка BLauncher. Выберите пустую папку"
                       + " или создайте новую — иначе удаление лаунчера затронет чужие файлы.";

            // И последнее — можно ли туда вообще писать. Проверять надо здесь, при выборе папки,
            // а не в ходе установки.
            //
            // Раньше этой проверки не было: путь вида C:\Program Files\BLauncher проходил все
            // проверки выше (не корень, не Windows, не известная пользовательская папка, пустой),
            // а потом Directory.CreateDirectory в EnsureDirectory падал с
            // UnauthorizedAccessException за одну десятую секунды. Человек получал системное
            // «Отказано в доступе к пути», ничего не понимал и пробовал снова. В воронке это
            // видно буквально: два отказа с одного адреса, стадия start, 0.1 и 0.0 секунды,
            // с разницей в пятнадцать секунд — вторая попытка и уход.
            string denied = CheckCanCreate(resolved);
            if (denied != null) return denied;

            return null;
        }

        /// <summary>
        ///  Можно ли создать эту папку и писать в неё. Возвращает текст проблемы или null.
        ///
        ///  <para>Проверка настоящая, а не по именам: создаёт отсутствующие каталоги, пишет и
        ///  удаляет пробный файл, после чего убирает за собой ровно то, что создала. Иначе не
        ///  отловить ни «Program Files» без прав администратора, ни контролируемый доступ к
        ///  папкам в Защитнике, ни политику организации на диске.</para>
        /// </summary>
        public static string CheckCanCreate(string dir)
        {
            List<string> created = new List<string>();
            try
            {
                // Собираем список каталогов, которых ещё нет: удалять потом будем только их.
                string probeDir = dir;
                for (string walk = dir; !string.IsNullOrEmpty(walk) && !Directory.Exists(walk); )
                {
                    created.Add(walk);
                    string parent = Path.GetDirectoryName(walk);
                    if (string.IsNullOrEmpty(parent) || parent == walk) break;
                    walk = parent;
                }

                Directory.CreateDirectory(probeDir);

                string probe = Path.Combine(probeDir, ".blauncher-write-test.tmp");
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return null;
            }
            catch (UnauthorizedAccessException)
            {
                return "Windows не разрешает запись в эту папку.\n\n"
                     + "Чаще всего так бывает с Program Files (нужны права администратора), "
                     + "а ещё причиной бывает антивирус или «Контролируемый доступ к папкам» "
                     + "в Защитнике Windows.\n\n"
                     + "Проще всего поставить в папку по умолчанию: " + DefaultDir();
            }
            catch (Exception ex)
            {
                return "В эту папку нельзя писать: " + ex.Message
                     + "\n\nПопробуйте папку по умолчанию: " + DefaultDir();
            }
            finally
            {
                // Убираем за собой. Удаляем изнутри наружу и только пустые — то, что создали сами.
                for (int i = 0; i < created.Count; i++)
                {
                    try
                    {
                        if (Directory.Exists(created[i]) &&
                            Directory.GetFileSystemEntries(created[i]).Length == 0)
                            Directory.Delete(created[i]);
                    }
                    catch (Exception ex) { Log.Write("write-test cleanup: " + ex.Message); }
                }
            }
        }

        /// <summary>
        ///  Проверки, зависящие ТОЛЬКО от самого пути: корень диска, папка Windows, известные
        ///  пользовательские папки, длина, недопустимые символы. Содержимое папки не смотрит.
        ///
        ///  <para>Отделено от ValidateTargetDir потому, что нужно в двух разных ситуациях.
        ///  При выборе папки установки важно и содержимое (туда нельзя ставить поверх чужих
        ///  файлов). А перед рекурсивным удалением в ScheduleSelfDelete содержимое проверять
        ///  уже бессмысленно: файлы лаунчера, включая install.json, к тому моменту удалены, и
        ///  полная проверка отвергала бы любой путь — то есть сломала бы галочку «удалить
        ///  игровые данные». Там нужна именно форма пути.</para>
        /// </summary>
        public static string ValidateDirShape(string dir)
        {
            if (string.IsNullOrEmpty(dir)) return "Укажите папку установки.";
            if (dir.IndexOfAny(Path.GetInvalidPathChars()) >= 0) return "В пути есть недопустимые символы.";

            // Проверяем ИМЕННО введённый путь: «C:» после GetFullPath превратился бы в текущую папку
            // процесса (обычно папку, откуда запущен установщик), и корень диска не был бы замечен.
            string bare = dir.Trim().TrimEnd('\\', '/');
            if (bare.Length == 2 && bare[1] == ':') return "Укажите папку на диске, а не сам диск.";
            if (bare.Length == 0) return "Укажите папку установки.";

            string full;
            try { full = Path.GetFullPath(dir.Trim()); } catch { return "Такой путь нельзя использовать."; }
            if (string.Equals(full.TrimEnd('\\'), Path.GetPathRoot(full).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                return "Нельзя ставить лаунчер прямо в корень диска — выберите папку.";

            string windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            if (windows.Length > 0 && full.StartsWith(windows, StringComparison.OrdinalIgnoreCase))
                return "В папку Windows ставить нельзя.";
            if (full.Length > 150) return "Слишком длинный путь — выберите папку покороче.";
            if (Path.GetFileName(full.TrimEnd('\\')).Length == 0) return "Укажите папку установки.";

            // Известные пользовательские папки целиком. Ставить лаунчер прямо в них нельзя
            // потому, что удаление вычищает из папки установки ВСЁ, кроме launcher_root и
            // config — то есть снесло бы рабочий стол или «Документы» вместе с лаунчером.
            string trimmed = full.TrimEnd('\\');
            string[] offLimits = {
                Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
                Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                Environment.GetFolderPath(Environment.SpecialFolder.Programs),
                Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads")
            };
            foreach (string bad in offLimits)
            {
                if (string.IsNullOrEmpty(bad)) continue;
                if (string.Equals(trimmed, bad.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                    return "Нельзя ставить лаунчер прямо в эту папку — создайте внутри неё отдельную,"
                           + " например «BLauncher».";
            }

            return null;
        }

        private static void AddUrl(List<string> urls, string url)
        {
            if (string.IsNullOrEmpty(url)) return;
            foreach (string known in urls)
                if (string.Equals(known, url, StringComparison.OrdinalIgnoreCase)) return;
            urls.Add(url);
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }

        /// <summary>
        ///  Сверка sha256 скачанного архива с тем, что опубликовал сервер.
        ///
        ///  <para>Раньше оба случая «хеша нет» и «хеш не похож на sha256» приводили к молчаливому
        ///  возврату, то есть к установке без всякой проверки. Проверяется при этом архив с
        ///  runtime — то есть с Java, кодом, который будет исполняться при каждом запуске игры.
        ///  Для такого «при сомнении пропустить» — неверный дефолт, поэтому теперь это отказ.</para>
        ///
        ///  <para>Исключение одно: установка из локального файла (/ZIP=...), который пользователь
        ///  выбрал сам и для которого на сервере хеша и не предполагается.</para>
        /// </summary>
        private static void VerifyHash(string path, string expected, bool localFile)
        {
            string want = (expected ?? "").Replace(" ", "").Replace("-", "").Trim();

            if (want.Length == 0)
            {
                if (localFile)
                {
                    Log.Write("sha256: локальный файл, сверять не с чем — пропускаю");
                    return;
                }
                throw Zip.Bad("sha_missing",
                    "Сервер не сообщил sha256 для этого архива, поэтому проверить его целостность"
                    + " нельзя. Установка остановлена. Попробуйте позже или поставьте из ZIP-файла,"
                    + " скачанного с сайта вручную.");
            }
            if (want.Length != 64)
            {
                throw Zip.Bad("sha_bad",
                    "Сервер сообщил sha256 в непонятном виде (" + want.Length + " символов вместо 64),"
                    + " проверить архив нельзя. Установка остановлена.");
            }

            string actual;
            using (FileStream stream = File.OpenRead(path))
            using (SHA256 sha = SHA256.Create())
            {
                byte[] hash = sha.ComputeHash(stream);
                StringBuilder text = new StringBuilder(hash.Length * 2);
                foreach (byte value in hash) text.Append(value.ToString("x2", CultureInfo.InvariantCulture));
                actual = text.ToString();
            }

            if (!string.Equals(actual, want, StringComparison.OrdinalIgnoreCase))
                throw Zip.Bad("sha_mismatch",
                    "Скачанный архив не совпал по sha256 с тем, что указан на сервере — файл повреждён или подменён.");
            Log.Write("sha256 совпал: " + actual);
        }

        public static void EnsureDirectory(string dir)
        {
            if (Directory.Exists(dir)) return;
            try
            {
                Directory.CreateDirectory(dir);
            }
            catch (UnauthorizedAccessException ex)
            {
                // Сообщение, а не системное «Отказано в доступе к пути». Сюда мы попадаем, только
                // если проверка при выборе папки почему-то не сработала (например, права отобрали
                // между нажатием «Установить» и этой секундой), но текст всё равно должен
                // говорить, что делать.
                throw new UnauthorizedAccessException(
                    "Не удалось создать папку " + dir + ": Windows не разрешает запись.\n\n"
                    + "Нужны права администратора, либо мешает антивирус или «Контролируемый "
                    + "доступ к папкам» в Защитнике Windows. Выберите другую папку — например "
                    + DefaultDir(), ex);
            }
        }

        public static void CheckWritable(string dir)
        {
            string probe = Path.Combine(dir, ".blauncher-write-test.tmp");
            try
            {
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
            }
            catch (Exception ex)
            {
                throw new IOException("В эту папку нельзя писать: " + ex.Message, ex);
            }
        }

        private static void CheckSpace(string dir, long needed)
        {
            try
            {
                string root = Path.GetPathRoot(Path.GetFullPath(dir));
                if (string.IsNullOrEmpty(root)) return;
                DriveInfo drive = new DriveInfo(root);
                if (drive.AvailableFreeSpace < needed)
                    throw new IOException(string.Format(
                        "На диске {0} недостаточно места: нужно {1}, свободно {2}.\n\n"
                        + "Освободите место или выберите папку на другом диске (кнопка «Обзор»). "
                        + "Для самой игры потом понадобится ещё около 1 ГБ.",
                        root, Size(needed), Size(drive.AvailableFreeSpace)));
            }
            catch (IOException) { throw; }
            catch (Exception ex) { Log.Write("space: " + ex.Message); }
        }

        public static string Size(long bytes)
        {
            if (bytes <= 0) return "0 МБ";
            double mb = bytes / 1048576.0;
            if (mb >= 1024) return (mb / 1024.0).ToString("0.00", CultureInfo.CurrentCulture) + " ГБ";
            return mb.ToString("0.0", CultureInfo.CurrentCulture) + " МБ";
        }

        public static void KillLauncher(string dir, ProgressSink sink)
        {
            Process[] running = Process.GetProcessesByName("BLauncher");
            if (running.Length == 0) return;

            string expected;
            try { expected = Path.GetFullPath(dir).TrimEnd('\\'); }
            catch { expected = dir.TrimEnd('\\'); }

            if (sink != null) sink.Say("Закрываю запущенный лаунчер…");
            foreach (Process process in running)
            {
                try
                {
                    // Закрываем только свой лаунчер — из этой самой папки. Если путь прочитать нельзя,
                    // процесс считается чужим: убить чужую игровую сессию хуже, чем не закрыть свою.
                    string path = null;
                    try { path = process.MainModule.FileName; } catch (Exception ex) { Log.Write("kill: путь pid " + process.Id + " — " + ex.Message); }
                    if (string.IsNullOrEmpty(path)) continue;

                    string folder = Path.GetDirectoryName(path);
                    if (string.IsNullOrEmpty(folder)) continue;
                    if (!string.Equals(folder.TrimEnd('\\'), expected, StringComparison.OrdinalIgnoreCase)) continue;

                    process.CloseMainWindow();
                    if (!process.WaitForExit(4000))
                    {
                        process.Kill();
                        process.WaitForExit(5000);
                    }
                    Log.Write("закрыт работающий BLauncher pid=" + process.Id);
                }
                catch (Exception ex) { Log.Write("kill: " + ex.Message); }
            }
        }

        /// <summary>
        /// Лаунчеры, которые держат файлы встроенной Java (runtime\) и которых KillLauncher НЕ закроет.
        ///
        /// 11 октября установка упала на распаковке Java: «UnauthorizedAccess, runtime\bin\ucrtbase.dll»
        /// после минуты ожидания. Антивирус держит файл доли секунды, а тут файл был занят всё
        /// время. Человек увидел совет про антивирус, который не помогал, и через семь минут ушёл.
        ///
        /// java.exe в runtime\ из jpackage нет — эти файлы загружает только сам BLauncher.exe. А
        /// KillLauncher пропускает процесс, путь которого прочитать не может (лаунчер запущен от имени
        /// администратора), и такой лаунчер держит файлы до конца. Его и ищем: BLauncher с нечитаемым путём.
        ///
        /// 12 октября попробовал спрашивать Windows через Restart Manager (rstrtmgr.dll) — точнее, но
        /// VirusTotal ответил тремя новыми срабатываниями (CrowdStrike, SecureAge, Bkav: 5 из 63 против
        /// 2 у прежнего exe): этим API пользуются шифровальщики. Не возвращать.
        /// </summary>
        public static List<Process> StuckLaunchers(string dir)
        {
            List<Process> found = new List<Process>();
            foreach (Process process in Process.GetProcessesByName("BLauncher"))
            {
                try
                {
                    string path = process.MainModule.FileName;
                    if (!string.IsNullOrEmpty(path)) continue;   // путь читается — это забота KillLauncher
                }
                catch { }
                found.Add(process);
            }
            return found;
        }

        /// <summary>Лаунчер из этой папки запущен, и KillLauncher его закроет сам.</summary>
        public static bool ClosableLauncherRunning(string dir)
        {
            string expected;
            try { expected = Path.GetFullPath(dir).TrimEnd('\\'); }
            catch { expected = dir.TrimEnd('\\'); }
            foreach (Process process in Process.GetProcessesByName("BLauncher"))
            {
                try
                {
                    string folder = Path.GetDirectoryName(process.MainModule.FileName);
                    if (folder != null && string.Equals(folder.TrimEnd('\\'), expected, StringComparison.OrdinalIgnoreCase))
                        return true;
                }
                catch { }
            }
            return false;
        }

        /// <summary>
        /// Файлы встроенной Java сейчас загружены каким-то процессом: загруженную dll нельзя открыть
        /// на запись — Windows отвечает «нарушение совместного доступа» (IOException).
        /// </summary>
        public static bool RuntimeInUse(string dir)
        {
            foreach (string rel in new string[] { @"runtime\bin\server\jvm.dll", @"runtime\bin\java.dll", @"runtime\bin\ucrtbase.dll" })
            {
                string file = Path.Combine(dir, rel);
                if (!File.Exists(file)) continue;
                try
                {
                    using (new FileStream(file, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) { }
                }
                catch (IOException) { return true; }               // занят — загружен процессом
                catch (UnauthorizedAccessException) { }            // «только чтение» и т.п. — не наш случай
            }
            return false;
        }

        /// <summary>
        /// Имя ресурса с вшитой частью сборки: папка runtime\, то есть Java 21.0.10.
        ///
        /// Раньше здесь лежал лаунчер (BLauncher.exe + app\), а Java качалась. Почему половины
        /// поменяли местами — подробно в Site.LauncherUrl.
        /// </summary>
        public const string RuntimeResource = "BLauncherSetup.runtime.zip";

        /// <summary>Маленький exe без вшитой части — он же ложится в папку установки как uninstall.exe.</summary>
        public const string UninstallerResource = "BLauncherSetup.uninstall.exe";

        /// <summary>Кладёт рядом uninstall.exe. Это отдельная маленькая сборка того же установщика:
        /// копировать сюда «толстый» exe с вшитой Java значило бы занимать лишние 55 МБ.</summary>
        private static void WriteUninstaller(string dir)
        {
            string target = Path.Combine(dir, "uninstall.exe");
            try
            {
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(UninstallerResource))
                {
                    if (stream != null)
                    {
                        using (FileStream output = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None))
                            stream.CopyTo(output);
                        Log.Write("uninstall.exe записан из ресурса: " + new FileInfo(target).Length + " байт");
                        return;
                    }
                }
                // В сборке без ресурса (например, запущен сам uninstall.exe) — просто копируем себя.
                File.Copy(Application.ExecutablePath, target, true);
                Log.Write("uninstall.exe скопирован из себя");
            }
            catch (Exception ex)
            {
                Log.Write("uninstall.exe: " + ex.Message);
            }
        }

        /// <summary>Кладёт пару «адрес → хеш», пропуская пустые адреса.</summary>
        private static void Put(Dictionary<string, string> map, string url, string hash)
        {
            if (!string.IsNullOrEmpty(url) && !map.ContainsKey(url)) map[url] = hash;
        }

        /// <summary>Размер распакованной вшитой части (0, если ресурса нет).</summary>
        public static long EmbeddedSize()
        {
            try
            {
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(RuntimeResource))
                {
                    if (stream == null) return 0;
                    using (ZipArchive archive = new ZipArchive(stream, ZipArchiveMode.Read))
                        return Zip.ArchiveSize(archive);
                }
            }
            catch (Exception ex)
            {
                Log.Write("embedded size: " + ex.Message);
                return 0;
            }
        }

        /// <summary>Распаковывает вшитую Java — без сети, это самая тяжёлая часть установки.</summary>
        private static void ExtractEmbedded(string dir, ProgressSink sink, double from, double to)
        {
            using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(RuntimeResource))
            {
                if (stream == null)
                    throw new IOException(
                        "Во встроенной части установщика нет данных — файл повреждён. Скачайте установщик заново.");
                using (ZipArchive archive = new ZipArchive(stream, ZipArchiveMode.Read))
                {
                    long total = Zip.ArchiveSize(archive);
                    Log.Write("встроенная Java: " + total + " байт распакованного");
                    Zip.ExtractArchive(archive, dir, sink, from, to, total);
                }
            }
        }

        /// <summary>
        /// Качает первый рабочий архив из списка адресов и возвращает его распакованный размер.
        /// Первая попытка идёт как настроена система, дальше — напрямую: системный прокси бывает
        /// медленным и рвёт длинные загрузки, а обрыв связи больше не начинает загрузку с нуля.
        /// </summary>
        /// <summary>Сколько ждать возвращения связи без единого скачанного байта, прежде чем сдаться.</summary>
        internal static int ReconnectBudgetSeconds = 180;

        /// <summary>Сбой сети (а не ответ сервера и не диск): после него есть смысл переподключиться.</summary>
        internal static bool IsNetworkError(Exception ex)
        {
            for (Exception e = ex; e != null; e = e.InnerException)
            {
                WebException web = e as WebException;
                if (web != null)
                {
                    switch (web.Status)
                    {
                        case WebExceptionStatus.ConnectFailure:
                        case WebExceptionStatus.NameResolutionFailure:
                        case WebExceptionStatus.ProxyNameResolutionFailure:
                        case WebExceptionStatus.Timeout:
                        case WebExceptionStatus.ConnectionClosed:
                        case WebExceptionStatus.ReceiveFailure:
                        case WebExceptionStatus.SendFailure:
                        case WebExceptionStatus.KeepAliveFailure:
                        case WebExceptionStatus.PipelineFailure:
                        case WebExceptionStatus.SecureChannelFailure:
                            return true;
                    }
                    return false;   // ProtocolError (404 и т.п.) — это ответ сервера, не обрыв
                }
                if (e is System.Net.Sockets.SocketException) return true;
                if (e is IOException && e.Message != null && e.Message.StartsWith("Файл скачан не полностью")) return true;
            }
            return false;
        }

        /// <summary>Адреса одного и того же файла — у них один опубликованный хеш.</summary>
        private static bool SameFile(Dictionary<string, string> hashes, string a, string b)
        {
            string ha, hb;
            return hashes != null && hashes.TryGetValue(a, out ha) && hashes.TryGetValue(b, out hb)
                && !string.IsNullOrEmpty(ha) && string.Equals(ha, hb, StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>Пауза перед переподключением с обратным отсчётом в окне; 5, 10, 20, затем по 30 с.</summary>
        internal static void WaitForReconnect(ProgressSink sink, int round)
        {
            int seconds = Math.Min(30, 5 << Math.Min(round, 3));
            for (int left = seconds; left > 0; left--)
            {
                sink.Check();
                sink.Say("Нет связи с сервером — переподключусь через " + left + " с. Скачанное сохранено.");
                Thread.Sleep(1000);
            }
        }

        private static long Fetch(List<string> urls, string target, ProgressSink sink, double from, double to,
                                  Dictionary<string, string> hashes, bool needLauncher, bool needRuntime)
        {
            Exception lastError = null;
            long unpacked = 0;
            string currentUrl = null;

            // Переподключение (21.10.2026): пропавшая на полминуты связь раньше сжигала все три
            // попытки за пару секунд, и установка падала. Теперь, пока нет связи, попытки не
            // тратятся — установщик ждёт её до ReconnectBudgetSeconds и докачивает с места обрыва.
            DateTime offlineSince = DateTime.MinValue;   // MinValue — связь есть
            int waits = 0;
            for (int attempt = 1; attempt <= 3 && unpacked <= 0; attempt++)
            {
                sink.Check();
                bool viaProxy = attempt == 1 && waits == 0;
                bool networkDown = false;

                foreach (string url in urls)
                {
                    // Докачивать можно только тот же самый файл: при смене адреса начинаем заново —
                    // кроме адресов с тем же хешем (blauncher-launcher.zip и его копия с версией).
                    if (currentUrl != null && !string.Equals(currentUrl, url, StringComparison.OrdinalIgnoreCase)
                        && !SameFile(hashes, currentUrl, url))
                        TryDelete(target);
                    currentUrl = url;

                    try
                    {
                        sink.Say(viaProxy
                            ? "Скачиваю сборку с blauncher.ru" + (attempt > 1 ? " (попытка " + attempt + ")" : "") + "…"
                            : "Скачиваю напрямую, минуя системный прокси…");

                        string expectedHash = null;
                        if (hashes != null) hashes.TryGetValue(url, out expectedHash);
                        long baseline = -1;
                        DateTime started = DateTime.Now;
                        long lastReport = 0;

                        Net.Download(url, target, delegate(long received, long total)
                        {
                            if (baseline < 0) { baseline = received; started = DateTime.Now; }
                            if (received - baseline > 256 * 1024) offlineSince = DateTime.MinValue;   // связь есть
                            double seconds = Math.Max(0.5, (DateTime.Now - started).TotalSeconds);

                            // Прокси, который «еле тянет», лучше обойти: если за 12 секунд набежало
                            // меньше 300 КБ/с — бросаем эту попытку и идём напрямую (файл не теряем,
                            // следующая попытка докачает его с места обрыва).
                            if (viaProxy && seconds > 12 && (received - baseline) / seconds < 300 * 1024)
                                throw new IOException("через системный прокси загрузка идёт слишком медленно");

                            if (DateTime.Now.Ticks - lastReport < 700000) return;   // не чаще 7 раз в секунду
                            lastReport = DateTime.Now.Ticks;
                            double share = total > 0 ? (double)received / total : 0;
                            sink.At(from + (to - from) * share);
                            string tail = total > 0
                                ? Size(received) + " из " + Size(total) + " (" + (share * 100).ToString("0", CultureInfo.CurrentCulture) + "%)"
                                : Size(received);
                            sink.Say("Скачиваю сборку: " + tail + " · " +
                                     Size((long)((received - baseline) / seconds)) + "/с");
                        }, sink, viaProxy);

                        sink.Say("Проверяю архив…");
                        // Хеш берётся по тому адресу, с которого файл реально приехал: у архива
                        // лаунчера это sha256Launcher, у полной сборки — sha256Zip, оба пишет
                        // build.ps1. Прежний код сверял только полную сборку, а архив с Java
                        // принимал без проверки, потому что сервер его хеш не публиковал. Теперь
                        // Java вообще не качается (она в exe), так что непроверяемого пути нет.
                        if (!string.IsNullOrEmpty(expectedHash)) VerifyHash(target, expectedHash, false);
                        else Log.Write("sha256: для " + url + " хеш не опубликован — не сверяю");
                        unpacked = Zip.Inspect(target, needLauncher, needRuntime);
                        Log.Write("архив ок (" + url + "), распакованного " + unpacked + " байт");
                        break;
                    }
                    catch (OperationCanceledException) { throw; }
                    catch (InvalidDataException ex)
                    {
                        // Скачалось, но это не архив (например, страница ошибки) — файл бесполезен.
                        lastError = ex;
                        Log.Write("download " + url + ": " + ex.Message);
                        TryDelete(target);
                    }
                    catch (Exception ex)
                    {
                        // Обрыв связи — файл оставляем, следующая попытка докачает его с места обрыва.
                        lastError = ex;
                        Log.Write("download " + url + ": " + ex.Message);
                        // Нет сети — другие адреса того же сервера тоже не ответят, а переход на
                        // полную сборку стёр бы уже скачанное. Ждём связь и продолжаем этот же файл.
                        if (IsNetworkError(ex)) { networkDown = true; break; }
                    }
                }

                if (unpacked <= 0 && networkDown && waits < 60)
                {
                    if (offlineSince == DateTime.MinValue) offlineSince = DateTime.Now;
                    if ((DateTime.Now - offlineSince).TotalSeconds < ReconnectBudgetSeconds)
                    {
                        WaitForReconnect(sink, waits++);
                        attempt--;   // ожидание связи попыткой не считается
                        continue;
                    }
                }

                if (unpacked <= 0)
                {
                    sink.Say("Сборку получить не удалось — пробую ещё раз…");
                    Thread.Sleep(2000);
                }
            }

            if (unpacked <= 0)
            {
                TryDelete(target);
                throw new IOException(
                    "Не удалось скачать сборку с blauncher.ru" +
                    (lastError == null ? "." : ": " + lastError.Message) +
                    "\nПроверьте интернет и повторите, либо скачайте сборку на сайте и укажите её кнопкой «Из ZIP-файла…».",
                    lastError);
            }
            return unpacked;
        }

        /// <summary>Полный цикл установки. Возвращает текст для окна «Готово».</summary>
        public static string Install(InstallJob job, ProgressSink sink)
        {
            // Обёртка вокруг установки: сообщает серверу о начале, об успехе и о срыве.
            // Оба вызывающих — и окно, и тихий режим — получают это без своего кода, а сами
            // события не влияют на ход установки (см. Stats): исключение всегда летит дальше.
            string reported = string.IsNullOrEmpty(job.Version) ? BuildInfo.DefaultVersion : job.Version;
            StatsRun run = Stats.Begin(reported);
            try
            {
                string summary = InstallCore(job, sink, run);
                Stats.Done(run);
                return summary;
            }
            catch (OperationCanceledException ex)
            {
                Stats.Failed(run, true, ex);
                throw;
            }
            catch (Exception ex)
            {
                Stats.Failed(run, false, ex);
                throw;
            }
        }

        /// <summary>Полный цикл установки. Возвращает текст для окна «Готово».</summary>
        private static string InstallCore(InstallJob job, ProgressSink sink, StatsRun run)
        {
            sink.At(0.01);
            sink.Say("Готовлю папку установки…");
            // Шаг пишется в телеметрию вместе с именем исключения: внутри стадии start три разных
            // операции, и без этого в отчёте стояло просто «UnauthorizedAccessException» —
            // понять, папку не создать или в неё не записать, было нельзя.
            if (run != null) run.Step = "mkdir";
            EnsureDirectory(job.Dir);
            if (run != null) run.Step = "write";
            CheckWritable(job.Dir);
            if (run != null) run.Step = "kill";
            KillLauncher(job.Dir, sink);
            // Окно уже спросило про занятые файлы Java (SetupForm.Install). Если они всё равно заняты —
            // тихая установка, программу открыли заново или KillLauncher не смог закрыть лаунчер, —
            // ждать минуту на файле и ругать антивирус бессмысленно: сразу понятная ошибка.
            if (run != null) run.Step = "runtime_busy";
            if (RuntimeInUse(job.Dir))
            {
                bool stuck = StuckLaunchers(job.Dir).Count > 0;
                throw new IOException("Файлы Java в папке " + job.Dir + " заняты"
                    + (stuck ? " запущенным BLauncher (похоже, от имени администратора)" : " другой программой")
                    + ". Закройте BLauncher и Minecraft (при необходимости через диспетчер задач) и запустите установку ещё раз.");
            }
            if (run != null) run.Step = null;

            string version =string.IsNullOrEmpty(job.Version) ? BuildInfo.DefaultVersion : job.Version;
            long installed = 0;

            if (!string.IsNullOrEmpty(job.ZipPath))
            {
                // Локальный архив содержит всю сборку — ставим прямо из него.
                sink.Say("Беру сборку из файла: " + Path.GetFileName(job.ZipPath));
                sink.At(0.10);
                Stats.Mark(run, "zip_verify");
                // Файл выбрал сам пользователь через /ZIP= или кнопку «Из ZIP-файла…» —
                // сверять его не с чем, и отказываться тут не за что.
                VerifyHash(job.ZipPath, job.ZipSha256, true);
                // Архив лаунчера без Java (blauncher-launcher.zip) — тоже годится: Java вшита в этот
                // exe. 20.10.2026 (3cdff403) три попытки «Из ZIP-файла…» подряд кончились отказом
                // «нет папки runtime», и человек так и не поставил лаунчер.
                bool ownJava = Zip.HasRuntime(job.ZipPath);
                long zipBytes = Zip.Inspect(job.ZipPath, true, false);
                long embedded = ownJava ? 0 : EmbeddedSize();
                CheckSpace(job.Dir, zipBytes + embedded + 32L * 1024 * 1024);
                double zipFrom = 0.12;
                if (!ownJava)
                {
                    sink.Say("В архиве нет Java — беру встроенную…");
                    Stats.Mark(run, "java_extract");
                    ExtractEmbedded(job.Dir, sink, 0.12, 0.40);
                    installed += embedded;
                    zipFrom = 0.40;
                }
                sink.Say("Распаковываю в " + job.Dir + "…");
                Stats.Mark(run, "zip_extract");
                Zip.Extract(job.ZipPath, job.Dir, sink, zipFrom, 0.92, zipBytes);
                installed += zipBytes;
            }
            else
            {
                // 1. Вшитая часть: Java. Она самая тяжёлая, но и самая неизменная — поэтому
                //    и лежит внутри exe (разбор в Site.LauncherUrl).
                long embedded = EmbeddedSize();
                sink.Say("Распаковываю встроенную Java…");
                sink.At(0.02);

                long expected = embedded + (job.LauncherSize > 0 ? job.LauncherSize : job.ZipSize);
                // Шаг в телеметрию: 9 октября один ПК трижды подряд упал здесь с голым «IOException»
                // на стадии start — понять, что это нехватка места, удалось только перебором кода.
                if (run != null) run.Step = "space";
                if (expected > 0) CheckSpace(job.Dir, expected * 3 + 64L * 1024 * 1024);
                if (run != null) run.Step = null;

                Stats.Mark(run, "java_extract");
                ExtractEmbedded(job.Dir, sink, 0.02, 0.22);
                installed += embedded;
                sink.Check();

                // 2. Лаунчер и библиотеки — приезжают с сервера отдельным архивом (38 МБ вместо
                //    93 МБ полной сборки); если его нет, берём полную сборку.
                string tempZip = Path.Combine(Path.GetTempPath(), "BLauncher_dl_" + Guid.NewGuid().ToString("N") + ".zip");
                try
                {
                    List<string> urls = new List<string>();
                    AddUrl(urls, job.LauncherUrl);
                    AddUrl(urls, Site.LauncherUrl);                         // blauncher-launcher.zip
                    AddUrl(urls, Site.LauncherVersionedUrlFor(version));    // он же под именем с версией
                    AddUrl(urls, job.ZipUrl);                               // полная сборка — запасной путь
                    AddUrl(urls, Site.ZipUrlFor(version));
                    AddUrl(urls, Site.ZipApiFor(version));

                    // Какому адресу какой хеш соответствует. Адреса лаунчера — все три один и тот
                    // же файл (имя без версии это копия версионного), поэтому и хеш у них один.
                    Dictionary<string, string> hashes =
                        new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    if (!string.IsNullOrEmpty(job.LauncherSha256))
                    {
                        Put(hashes, job.LauncherUrl, job.LauncherSha256);
                        Put(hashes, Site.LauncherUrl, job.LauncherSha256);
                        Put(hashes, Site.LauncherVersionedUrlFor(version), job.LauncherSha256);
                    }
                    if (!string.IsNullOrEmpty(job.ZipSha256))
                    {
                        Put(hashes, job.ZipUrl, job.ZipSha256);
                        Put(hashes, Site.ZipUrlFor(version), job.ZipSha256);
                        Put(hashes, Site.ZipApiFor(version), job.ZipSha256);
                    }

                    // Самая долгая и самая ненадёжная стадия — здесь теряются те, у кого
                    // слабый или рвущийся интернет, и именно её долю в отчёте интересно видеть.
                    Stats.Mark(run, "launcher_download");
                    // needRuntime=false: runtime\ уже лежит на диске из вшитой части, и требовать
                    // его в скачанном архиве значило бы отвергать как раз тот архив, который мы
                    // и просили. BLauncher.exe, наоборот, обязателен — он только в нём.
                    long got = Fetch(urls, tempZip, sink, 0.24, 0.90, hashes, true, false);
                    installed += got;

                    CheckSpace(job.Dir, got + 32L * 1024 * 1024);
                    sink.Say("Распаковываю в " + job.Dir + "…");
                    Stats.Mark(run, "launcher_extract");
                    Zip.Extract(tempZip, job.Dir, sink, 0.90, 0.94, got);
                }
                finally { TryDelete(tempZip); }
            }

            string exe = GuiExe(job.Dir);
            if (!File.Exists(exe)) throw new IOException("После распаковки не найден " + exe);
            if (!Directory.Exists(Path.Combine(job.Dir, "runtime")))
                throw new IOException(
                    "После распаковки нет папки runtime — установка неполная. Запустите установщик ещё раз.");

            sink.Check();
            sink.At(0.95);
            sink.Say("Ярлыки и запись удаления…");
            Stats.Mark(run, "finalize");
            AfterExtract(job, installed, run);

            sink.At(1.0);
            string done = "BLauncher " + (string.IsNullOrEmpty(job.Version) ? "" : job.Version + " ") +
                          "установлен в " + job.Dir;
            if ((job.Desktop && !job.DesktopCreated) || (job.StartMenu && !job.StartMenuCreated))
            {
                done += "\nЯрлык создать не удалось — запускайте " + exe + " напрямую.";
                Log.Write("ярлыки не созданы: desktop=" + job.DesktopCreated + " startmenu=" + job.StartMenuCreated);
            }
            sink.Say("Готово. " + done);

            if (job.Launch)
            {
                sink.Say("Запускаю лаунчер…");
                ProcessStartInfo start = new ProcessStartInfo(exe);
                start.WorkingDirectory = job.Dir;
                start.UseShellExecute = true;
                Process.Start(start);
                Log.Write("запущен " + exe);
            }

            return done;
        }

        private static void AfterExtract(InstallJob job, long unpacked, StatsRun run)
        {
            string exe = GuiExe(job.Dir);
            string desktop = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "BLauncher.lnk");
            string programs = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "BLauncher.lnk");

            // Отдельные стадии: 20.10.2026 установка 3cdff403 дошла до «finalize» и замолчала — ни
            // done, ни fail. Ярлыки идут через COM WScript.Shell, который антивирус может держать,
            // поэтому видно должно быть, на каком действии встали.
            Stats.Mark(run, "shortcuts");
            job.DesktopCreated = Shortcut(job.Desktop, desktop, exe, job.Dir);
            job.StartMenuCreated = Shortcut(job.StartMenu, programs, exe, job.Dir);
            Stats.Mark(run, "uninstall_reg");
            WriteUninstaller(job.Dir);

            string installedAt = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
            string info = "{\r\n  \"version\": \"" + job.Version + "\",\r\n  \"installedAt\": \"" + installedAt +
                          "\",\r\n  \"installDir\": \"" + job.Dir.Replace("\\", "\\\\") + "\"\r\n}\r\n";
            try { File.WriteAllText(Path.Combine(job.Dir, "install.json"), info, new UTF8Encoding(false)); }
            catch (Exception ex) { Log.Write("install.json: " + ex.Message); }

            try
            {
                using (RegistryKey key = Registry.CurrentUser.CreateSubKey(
                           @"Software\Microsoft\Windows\CurrentVersion\Uninstall\BLauncher"))
                {
                    key.SetValue("DisplayName", "BLauncher");
                    key.SetValue("DisplayVersion", Site.Trim(job.Version));
                    key.SetValue("Publisher", "blauncher.ru");
                    key.SetValue("InstallLocation", job.Dir);
                    key.SetValue("DisplayIcon", exe);
                    key.SetValue("UninstallString", "\"" + Path.Combine(job.Dir, "uninstall.exe") + "\" /uninstall");
                    key.SetValue("QuietUninstallString", "\"" + Path.Combine(job.Dir, "uninstall.exe") + "\" /uninstall /S");
                    key.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
                    key.SetValue("EstimatedSize", (int)(unpacked / 1024), RegistryValueKind.DWord);
                    key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                    key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                }
            }
            catch (Exception ex) { Log.Write("registry: " + ex.Message); }
        }

        /// <summary>Создаёт (или убирает) ярлык. Возвращает false, если ярлык просили, а он не получился.</summary>
        private static bool Shortcut(bool create, string lnkPath, string target, string workingDir)
        {
            try
            {
                if (!create)
                {
                    if (File.Exists(lnkPath)) File.Delete(lnkPath);
                    return true;
                }
                string folder = Path.GetDirectoryName(lnkPath);
                if (!string.IsNullOrEmpty(folder) && !Directory.Exists(folder)) Directory.CreateDirectory(folder);

                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                if (shellType == null) throw new InvalidOperationException("WScript.Shell недоступен");
                object shell = Activator.CreateInstance(shellType);
                object link = null;
                try
                {
                    link = shellType.InvokeMember("CreateShortcut", System.Reflection.BindingFlags.InvokeMethod,
                        null, shell, new object[] { lnkPath });
                    Type linkType = link.GetType();
                    linkType.InvokeMember("TargetPath", System.Reflection.BindingFlags.SetProperty, null, link, new object[] { target });
                    linkType.InvokeMember("WorkingDirectory", System.Reflection.BindingFlags.SetProperty, null, link, new object[] { workingDir });
                    linkType.InvokeMember("IconLocation", System.Reflection.BindingFlags.SetProperty, null, link, new object[] { target + ",0" });
                    linkType.InvokeMember("Description", System.Reflection.BindingFlags.SetProperty, null, link, new object[] { "BLauncher — лаунчер Minecraft" });
                    linkType.InvokeMember("Save", System.Reflection.BindingFlags.InvokeMethod, null, link, null);
                    Log.Write("ярлык " + lnkPath);
                    return true;
                }
                finally
                {
                    if (link != null) { try { System.Runtime.InteropServices.Marshal.ReleaseComObject(link); } catch { } }
                    try { System.Runtime.InteropServices.Marshal.ReleaseComObject(shell); } catch { }
                }
            }
            catch (Exception ex)
            {
                // Ярлык — не повод считать установку неудачной, но и врать про него не надо.
                Log.Write("ярлык " + lnkPath + ": " + ex.Message);
                return false;
            }
        }

        public static string InstalledDirFromRegistry()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(
                           @"Software\Microsoft\Windows\CurrentVersion\Uninstall\BLauncher"))
                {
                    if (key == null) return "";
                    object value = key.GetValue("InstallLocation");
                    return value == null ? "" : value.ToString();
                }
            }
            catch { return ""; }
        }

        /// <summary>Похожа ли папка на установку BLauncher (а не на что-то чужое).</summary>
        /// <summary>
        ///  Папка — это установка, сделанная ЭТИМ установщиком. Признак один: install.json,
        ///  который пишет только AfterExtract.
        ///
        ///  <para>Раньше признаком считалось ещё и «есть BLauncher.exe и папка app». Под это
        ///  описание попадает дерево исходников проекта — в нём есть и то, и другое, — поэтому
        ///  установщик принимал его за существующую установку. В сочетании с тем, что кнопка
        ///  «Обзор» не дописывает BLauncher к папке, уже так названной, удаление из такой папки
        ///  снесло бы src, installer, build.ps1 и всё остальное: Uninstall удаляет все файлы и
        ///  все подкаталоги, кроме launcher_root и config.</para>
        /// </summary>
        public static bool LooksLikeInstall(string dir)
        {
            if (!Directory.Exists(dir)) return false;
            return File.Exists(Path.Combine(dir, "install.json"));
        }

        /// <summary>Папка пуста или её вообще нет — в такую ставить безопасно.</summary>
        public static bool IsEmptyOrMissing(string dir)
        {
            try
            {
                if (!Directory.Exists(dir)) return true;
                return Directory.GetFileSystemEntries(dir).Length == 0;
            }
            catch (Exception ex)
            {
                Log.Write("empty-check " + dir + ": " + ex.Message);
                return false;   // не смогли проверить — считаем занятой
            }
        }

        /// <summary>Возвращает число файлов и папок, которые удалить не удалось.</summary>
        public static int Uninstall(string dir, bool removeGameData, ProgressSink sink)
        {
            int failed = 0;
            dir = dir.TrimEnd('\\');

            sink.Say("Закрываю лаунчер…");
            KillLauncher(dir, sink);

            string self = Application.ExecutablePath;
            sink.Say("Удаляю файлы лаунчера…");
            if (Directory.Exists(dir))
            {
                foreach (string file in Directory.GetFiles(dir))
                {
                    if (string.Equals(file, self, StringComparison.OrdinalIgnoreCase)) continue;   // себя удалит cmd
                    try { File.Delete(file); }
                    catch (Exception ex) { failed++; Log.Write("uninstall file " + file + ": " + ex.Message); }
                }
                foreach (string folder in Directory.GetDirectories(dir))
                {
                    string name = Path.GetFileName(folder);
                    // Игровые файлы остаются на месте, а config — это настройки, профили и вход игрока.
                    if (!removeGameData &&
                        (string.Equals(name, "launcher_root", StringComparison.OrdinalIgnoreCase) ||
                         string.Equals(name, "config", StringComparison.OrdinalIgnoreCase)))
                        continue;
                    try { Directory.Delete(folder, true); }
                    catch (Exception ex) { failed++; Log.Write("uninstall dir " + folder + ": " + ex.Message); }
                }
            }

            sink.Say("Убираю ярлыки…");
            foreach (string lnk in new string[] {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "BLauncher.lnk"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "BLauncher.lnk") })
            {
                try { if (File.Exists(lnk)) File.Delete(lnk); }
                catch (Exception ex) { failed++; Log.Write("uninstall lnk " + lnk + ": " + ex.Message); }
            }

            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\BLauncher", false); }
            catch (Exception ex) { Log.Write("uninstall registry: " + ex.Message); }

            sink.Say(failed == 0 ? "Готово." : "Готово, но " + failed + " объектов удалить не удалось — подробности в логе.");
            ScheduleSelfDelete(dir, self, removeGameData);
            return failed;
        }

        /// <summary>
        ///  Себя удалить на ходу нельзя — просим cmd подождать пару секунд и подчистить.
        ///
        ///  <para>Путь перед рекурсивным удалением проверяется заново. Он приходит из install.json
        ///  или реестра, то есть из файлов, которые может отредактировать что угодно, работающее
        ///  от имени пользователя, — а строка ниже разворачивается в <c>rmdir /s /q</c>. Без
        ///  проверки это рекурсивно удаляющий примитив, чья цель берётся из редактируемого файла.</para>
        /// </summary>
        private static void ScheduleSelfDelete(string dir, string self, bool wholeFolder)
        {
            try
            {
                // Рекурсивное удаление — только по пути, который прошёл ту же проверку, что и
                // папка установки: не корень диска, не папка Windows, не рабочий стол и не
                // профиль целиком. Не прошёл — удаляем лишь сам uninstall.exe и пробуем снять
                // пустую папку, но рекурсию не запускаем.
                string reject = ValidateDirShape(dir);
                if (wholeFolder && reject != null)
                {
                    Log.Write("self-delete: рекурсивное удаление отменено, путь не прошёл проверку ("
                              + dir + "): " + reject);
                    wholeFolder = false;
                }

                // Себя удаляем ТОЛЬКО если мы лежим внутри удаляемой папки.
                //
                // Обычный случай — это uninstall.exe из папки установки, его и надо убрать. Но
                // тот же код выполняется, когда с ключом /UNINSTALL запускают скачанный
                // BLauncherSetup.exe из «Загрузок»: тогда прежняя строка стирала сам установщик,
                // и человек, решивший переставить лаунчер, обнаруживал, что качать больше нечего.
                // Поймано на своей же проверке — сборочный exe исчез после теста удаления.
                bool deleteSelf = false;
                try
                {
                    string selfDir = Path.GetDirectoryName(Path.GetFullPath(self));
                    string fullDir = Path.GetFullPath(dir).TrimEnd('\\');
                    deleteSelf = selfDir != null &&
                        selfDir.TrimEnd('\\').StartsWith(fullDir, StringComparison.OrdinalIgnoreCase);
                }
                catch (Exception ex) { Log.Write("self-delete: не понял путь " + self + ": " + ex.Message); }

                if (!deleteSelf)
                    Log.Write("self-delete: " + self + " вне папки " + dir + " — себя не удаляю");

                // Без консоли «timeout» не ждёт вовсе (он требует stdin), а ping ждёт всегда.
                string command = "/c ping -n 3 127.0.0.1 >nul";
                if (deleteSelf) command += " & del /f /q \"" + self + "\"";
                command += wholeFolder
                    ? " & rmdir /s /q \"" + dir + "\""
                    : " & rd \"" + dir + "\"";   // пустая папка удалится, с launcher_root — останется
                ProcessStartInfo psi = new ProcessStartInfo("cmd.exe", command);
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.WindowStyle = ProcessWindowStyle.Hidden;
                Process.Start(psi);
            }
            catch (Exception ex) { Log.Write("self-delete: " + ex.Message); }
        }
    }

    // ------------------------------------------------------------------ разбор командной строки

    internal sealed class SetupOptions
    {
        public bool Silent;
        public bool Uninstall;
        public bool RemoveData;
        public bool Help;
        public bool NoDesktop;
        public bool NoStartMenu;
        public bool NoLaunch;
        public bool ForceLaunch;
        public bool NoStats;
        public string Dir;
        public string Zip;

        public bool Desktop { get { return !NoDesktop; } }
        public bool StartMenu { get { return !NoStartMenu; } }

        /// <summary>В тихом режиме лаунчер по умолчанию не запускается — только по явному /LAUNCH.</summary>
        public bool Launch { get { return ForceLaunch || !Silent && !NoLaunch; } }

        public static SetupOptions Parse(string[] args)
        {
            SetupOptions options = new SetupOptions();
            foreach (string raw in args)
            {
                if (string.IsNullOrEmpty(raw)) continue;
                string arg = raw.Trim();
                string body = arg.StartsWith("/") || arg.StartsWith("-") ? arg.Substring(1) : arg;
                string key = body;
                string value = null;

                int eq = body.IndexOf('=');
                if (eq > 0) { key = body.Substring(0, eq); value = body.Substring(eq + 1).Trim('"'); }
                else
                {
                    int colon = body.IndexOf(':');
                    if (colon > 0 && body.Substring(0, colon).ToUpperInvariant() != "HTTP" &&
                        body.Substring(0, colon).ToUpperInvariant() != "HTTPS")
                    { key = body.Substring(0, colon); value = body.Substring(colon + 1).Trim('"'); }
                }

                switch (key.ToUpperInvariant())
                {
                    case "S": case "SILENT": case "QUIET": options.Silent = true; break;
                    case "UNINSTALL": options.Uninstall = true; break;
                    case "REMOVEDATA": options.RemoveData = true; break;
                    case "?": case "H": case "HELP": options.Help = true; break;
                    case "NODESKTOP": case "NODESKTOPSHORTCUT": options.NoDesktop = true; break;
                    case "NOSTARTMENU": options.NoStartMenu = true; break;
                    case "NOLAUNCH": options.NoLaunch = true; break;
                    case "LAUNCH": options.ForceLaunch = true; options.NoLaunch = false; break;
                    case "NOSTATS": options.NoStats = true; break;
                    case "DIR": case "D": if (!string.IsNullOrEmpty(value)) options.Dir = value; break;
                    case "ZIP": case "FROMZIP": if (!string.IsNullOrEmpty(value)) options.Zip = value; break;
                    default: Log.Write("неизвестный ключ: " + arg); break;
                }
            }
            // Удаление без /DIR — папка, где лежит сам uninstall.exe, если там стоит лаунчер. Раньше
            // всегда бралась папка по умолчанию, а запись в «Программах и компонентах» вызывает
            // uninstall.exe как раз без /DIR: у поставившего в свою папку «Удалить» сносило
            // %LOCALAPPDATA%\BLauncher, а его установка оставалась (поймано проверкой 21.10.2026).
            if (string.IsNullOrEmpty(options.Dir) && options.Uninstall)
            {
                try
                {
                    string own = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
                    if (!string.IsNullOrEmpty(own) && Installer.LooksLikeInstall(own)) options.Dir = own;
                }
                catch (Exception ex) { Log.Write("uninstall: не понял свою папку: " + ex.Message); }
            }
            if (string.IsNullOrEmpty(options.Dir)) options.Dir = Installer.DefaultDir();
            return options;
        }

        public static string HelpText()
        {
            return "BLauncherSetup.exe — установка лаунчера BLauncher\r\n\r\n" +
                   "  /DIR=<путь>   папка установки (по умолчанию %LOCALAPPDATA%\\BLauncher)\r\n" +
                   "  /ZIP=<файл>   поставить из локального zip, не обращаясь к серверу\r\n" +
                   "  /NODESKTOP    не создавать ярлык на рабочем столе\r\n" +
                   "  /NOSTARTMENU  не создавать ярлык в меню «Пуск»\r\n" +
                   "  /NOLAUNCH     не запускать лаунчер после установки\r\n" +
                   "  /S            тихая установка без окон (лаунчер не запускается, добавьте /LAUNCH)\r\n" +
                   "  /NOSTATS      не сообщать на сервер о ходе установки\r\n" +
                   "  /UNINSTALL    удалить лаунчер (/REMOVEDATA — вместе с launcher_root)\r\n";
        }
    }

    // ------------------------------------------------------------------ окно установки

    internal sealed class SetupForm : Form
    {
        private readonly SetupOptions _options;
        private readonly InstallJob _job;

        private Label _version;
        private TextBox _dir;
        private RoundedButton _browse;
        private RoundedButton _install;
        private RoundedButton _fromZip;
        private RoundedButton _close;
        private ToggleSwitch _desktop;
        private ToggleSwitch _startMenu;
        private ToggleSwitch _launch;
        private ProgressStrip _progress;
        private Label _status;
        private Label _note;

        private RemoteInfo _remote;
        private volatile bool _cancel;
        private bool _busy;
        private bool _manifestDone;

        /// <summary>Код возврата процесса: 0 — успех, 1 — ошибка, 2 — отменено.</summary>
        public int Result;

        public SetupForm(SetupOptions options)
        {
            _options = options;
            _job = new InstallJob();
            _job.Dir = options.Dir;
            _job.ZipPath = options.Zip;
            _job.Version = BuildInfo.DefaultVersion;

            Text = "Установка BLauncher";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.75f);
            ClientSize = new Size(700, 490);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            DoubleBuffered = true;
            AutoScaleDimensions = new SizeF(96f, 96f);
            AutoScaleMode = AutoScaleMode.Dpi;

            BuildUi();
            Shown += delegate { StartProbe(); };
        }

        private void BuildUi()
        {
            Label title = new Label();
            title.Text = "BLauncher";
            title.Font = Theme.Font(21f, FontStyle.Bold);
            title.ForeColor = Theme.Text;
            title.BackColor = Color.Transparent;
            title.AutoSize = true;
            title.Location = new Point(26, 20);
            Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "Установка лаунчера — выберите папку и ярлыки";
            subtitle.Font = Theme.Font(9.75f);
            subtitle.ForeColor = Theme.Muted;
            subtitle.BackColor = Color.Transparent;
            subtitle.AutoSize = true;
            subtitle.Location = new Point(29, 62);
            Controls.Add(subtitle);

            _version = new Label();
            _version.Text = "спрашиваю версию на сервере…";
            _version.Font = Theme.Font(9f);
            _version.ForeColor = Theme.Dim;
            _version.BackColor = Color.Transparent;
            _version.AutoSize = false;
            _version.TextAlign = ContentAlignment.MiddleRight;
            _version.Location = new Point(ClientSize.Width - 26 - 300, 30);
            _version.Size = new Size(300, 22);
            Controls.Add(_version);

            RoundedPanel pathCard = new RoundedPanel();
            pathCard.Fill = Theme.Surface;
            pathCard.BorderColor = Theme.Line;
            pathCard.Location = new Point(26, 100);
            pathCard.Size = new Size(ClientSize.Width - 52, 88);
            Controls.Add(pathCard);

            Label pathLabel = new Label();
            pathLabel.Text = "Куда установить лаунчер";
            pathLabel.Font = Theme.Font(9f);
            pathLabel.ForeColor = Theme.Muted;
            pathLabel.BackColor = Color.Transparent;
            pathLabel.AutoSize = true;
            pathLabel.Location = new Point(18, 12);
            pathCard.Controls.Add(pathLabel);

            RoundedPanel field = new RoundedPanel();
            field.Fill = Theme.Surface2;
            field.BorderColor = Theme.LineStrong;
            field.Radius = 8;
            field.Location = new Point(18, 38);
            field.Size = new Size(pathCard.Width - 36 - 100 - 10, 32);
            pathCard.Controls.Add(field);

            _dir = new TextBox();
            _dir.BorderStyle = BorderStyle.None;
            _dir.BackColor = Theme.Surface2;
            _dir.ForeColor = Theme.Text;
            _dir.Font = Theme.Font(10f);
            _dir.Text = _job.Dir;
            _dir.Location = new Point(10, 7);
            _dir.Width = field.Width - 20;
            field.Controls.Add(_dir);

            _browse = new RoundedButton();
            _browse.Text = "Обзор…";
            _browse.Kind = ButtonKind.Ghost;
            _browse.SurfaceColor = Theme.Surface;
            _browse.Location = new Point(pathCard.Width - 18 - 100, 38);
            _browse.Size = new Size(100, 32);
            _browse.Click += delegate { Browse(); };
            pathCard.Controls.Add(_browse);

            RoundedPanel optCard = new RoundedPanel();
            optCard.Fill = Theme.Surface;
            optCard.BorderColor = Theme.Line;
            optCard.Location = new Point(26, 200);
            optCard.Size = new Size(ClientSize.Width - 52, 146);
            Controls.Add(optCard);

            Label optLabel = new Label();
            optLabel.Text = "Ярлыки и запуск";
            optLabel.Font = Theme.Font(9f);
            optLabel.ForeColor = Theme.Muted;
            optLabel.BackColor = Color.Transparent;
            optLabel.AutoSize = true;
            optLabel.Location = new Point(18, 12);
            optCard.Controls.Add(optLabel);

            _desktop = MakeToggle("Ярлык на рабочем столе", _options.Desktop, 44);
            _startMenu = MakeToggle("Ярлык в меню «Пуск»", _options.StartMenu, 78);
            _launch = MakeToggle("Запустить BLauncher после установки", _options.Launch, 112);
            optCard.Controls.Add(_desktop);
            optCard.Controls.Add(_startMenu);
            optCard.Controls.Add(_launch);

            _note = new Label();
            _note.Text = "";
            _note.Font = Theme.Font(8.75f);
            _note.ForeColor = Theme.Dim;
            _note.BackColor = Color.Transparent;
            _note.AutoSize = false;
            _note.Location = new Point(26, 356);
            _note.Size = new Size(ClientSize.Width - 52, 18);
            Controls.Add(_note);

            _progress = new ProgressStrip();
            _progress.Location = new Point(26, 382);
            _progress.Size = new Size(ClientSize.Width - 52, 6);
            Controls.Add(_progress);

            _status = new Label();
            _status.Text = "Готов к установке.";
            _status.Font = Theme.Font(9.25f);
            _status.ForeColor = Theme.Muted;
            _status.BackColor = Color.Transparent;
            _status.AutoSize = false;
            _status.Location = new Point(26, 396);
            _status.Size = new Size(ClientSize.Width - 52, 20);
            Controls.Add(_status);

            _close = new RoundedButton();
            _close.Text = "Закрыть";
            _close.Kind = ButtonKind.Ghost;
            _close.Location = new Point(26, 424);
            _close.Size = new Size(110, 40);
            _close.Click += delegate { Close(); };
            Controls.Add(_close);

            _fromZip = new RoundedButton();
            _fromZip.Text = "Из ZIP-файла…";
            _fromZip.Kind = ButtonKind.Ghost;
            _fromZip.Location = new Point(312, 424);
            _fromZip.Size = new Size(160, 40);
            _fromZip.Click += delegate { InstallFromZip(); };
            Controls.Add(_fromZip);

            _install = new RoundedButton();
            _install.Text = "Установить";
            _install.Kind = ButtonKind.Accent;
            _install.Location = new Point(ClientSize.Width - 26 - 190, 424);
            _install.Size = new Size(190, 40);
            _install.Click += InstallOrCancel;
            Controls.Add(_install);

            AcceptButton = _install;
        }

        private ToggleSwitch MakeToggle(string text, bool value, int y)
        {
            ToggleSwitch toggle = new ToggleSwitch();
            toggle.Text = text;
            toggle.BackColor = Theme.Surface;
            toggle.Location = new Point(18, y);
            toggle.Size = new Size(600, 28);
            toggle.Checked = value;
            return toggle;
        }

        // ---------------------------------------------------------------- сервер

        private void StartProbe()
        {
            Thread worker = new Thread(delegate ()
            {
                RemoteInfo info = Installer.Probe();
                Post(delegate
                {
                    _remote = info;
                    _manifestDone = true;
                    _job.Version = info.Version;
                    _job.ZipUrl = info.ZipUrl;
                    _job.ZipSha256 = info.ZipSha256;
                    _job.LauncherUrl = info.LauncherUrl;
                    _job.LauncherSha256 = info.LauncherSha256;
                    _job.LauncherSize = info.LauncherSize;
                    _job.ZipSize = info.ZipSize;
                    if (info.FromServer)
                    {
                        long need = info.LauncherSize > 0 ? info.LauncherSize : info.ZipSize;
                        _version.Text = "на сервере " + info.Version + (need > 0 ? " · догрузится " + Installer.Size(need) : "");
                        _version.ForeColor = Theme.Accent;
                        _status.Text = "Java — внутри установщика" +
                                       (need > 0 ? ", с сервера догрузится лаунчер, " + Installer.Size(need) + "." : ".");
                    }
                    else
                    {
                        _version.Text = "сервер недоступен";
                        _version.ForeColor = Theme.Warn;
                        _status.Text = "Сервер не ответил — можно указать ZIP-файл кнопкой «Из ZIP-файла…».";
                    }
                    ShowNote();
                });
            });
            worker.IsBackground = true;
            worker.Name = "probe";
            worker.Start();
        }

        private void ShowNote()
        {
            string text = "";
            try
            {
                string root = Path.GetPathRoot(Path.GetFullPath(_job.Dir));
                if (!string.IsNullOrEmpty(root))
                {
                    DriveInfo drive = new DriveInfo(root);
                    text = "Свободно на " + root + ": " + Installer.Size(drive.AvailableFreeSpace) +
                           " · игровые файлы в launcher_root не трогаются";
                }
            }
            catch { }
            _note.Text = text;
        }

        // ---------------------------------------------------------------- действия

        private void Browse()
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Куда установить BLauncher";
                dialog.ShowNewFolderButton = true;
                string current = _dir.Text.Trim();
                if (Directory.Exists(current)) dialog.SelectedPath = current;
                else
                {
                    string parent = Path.GetDirectoryName(current);
                    if (!string.IsNullOrEmpty(parent) && Directory.Exists(parent)) dialog.SelectedPath = parent;
                }
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    string picked = dialog.SelectedPath;
                    if (string.Equals(Path.GetFileName(picked.TrimEnd('\\')), "BLauncher", StringComparison.OrdinalIgnoreCase))
                        _dir.Text = picked;
                    else
                        _dir.Text = Path.Combine(picked, "BLauncher");
                    _job.Dir = _dir.Text;
                    ShowNote();
                }
            }
        }

        private void InstallFromZip()
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "Выберите ZIP-архив сборки BLauncher";
                dialog.Filter = "Архив BLauncher (*.zip)|*.zip|Все файлы (*.*)|*.*";
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                _job.ZipPath = dialog.FileName;
                _status.Text = "Ставлю из файла: " + Path.GetFileName(dialog.FileName);
                Install();
            }
        }

        private string ValidateDir(string dir)
        {
            return Installer.ValidateTargetDir(dir);
        }

        private void Install()
        {
            if (_busy) return;

            // Сначала проверяем то, что ввели, и только потом нормализуем путь.
            string entered = _dir.Text.Trim();
            string problem = ValidateDir(entered);
            if (problem != null)
            {
                MessageBox.Show(this, problem, "Установка BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            try { _job.Dir = Path.GetFullPath(entered).TrimEnd('\\'); }
            catch { _job.Dir = entered.TrimEnd('\\'); }
            _dir.Text = _job.Dir;

            if (_job.ZipPath == null && !_manifestDone)
            {
                MessageBox.Show(this, "Ещё не получил ответ от сервера — подождите пару секунд или укажите ZIP-файл.",
                    "Установка BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }

            if (_job.ZipPath == null && _remote != null && !_remote.FromServer)
            {
                DialogResult answer = MessageBox.Show(this,
                    "Сервер blauncher.ru не отвечает. Попробовать всё равно?\n\n" +
                    "Если не получится — нажмите «Из ZIP-файла…» и укажите сборку, скачанную вручную.",
                    "Установка BLauncher", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (answer != DialogResult.Yes) return;
            }

            if (File.Exists(Installer.GuiExe(_job.Dir)))
            {
                DialogResult answer = MessageBox.Show(this,
                    "В папке уже есть BLauncher — файлы лаунчера будут обновлены,\n" +
                    "игровые файлы и настройки останутся на месте.\n" +
                    "Запущенный лаунчер будет закрыт.",
                    "Установка BLauncher", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
                if (answer != DialogResult.OK) return;
            }

            // Файлы Java в этой папке кто-то держит, и это не лаунчер, который закроет KillLauncher, —
            // см. Installer.StuckLaunchers. Иначе распаковка упадёт через минуту ожидания.
            while (Installer.RuntimeInUse(_job.Dir) && !Installer.ClosableLauncherRunning(_job.Dir))
            {
                List<Process> stuck = Installer.StuckLaunchers(_job.Dir);
                DialogResult answer = MessageBox.Show(this,
                    (stuck.Count > 0
                        ? "Запущен BLauncher, который установщик не может закрыть сам —\n" +
                          "скорее всего, он запущен от имени администратора.\n\n"
                        : "Файлы Java в этой папке заняты другой программой.\n\n") +
                    "Закройте BLauncher (и Minecraft, если он открыт) — при необходимости через\n" +
                    "диспетчер задач — и нажмите «Повторить».",
                    "Установка BLauncher", MessageBoxButtons.RetryCancel, MessageBoxIcon.Warning);
                if (answer != DialogResult.Retry) return;
            }

            if (_job.ZipPath != null && !File.Exists(_job.ZipPath))
            {
                MessageBox.Show(this, "Файл не найден: " + _job.ZipPath, "Установка BLauncher",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                _job.ZipPath = null;
                return;
            }

            _job.Desktop = _desktop.Checked;
            _job.StartMenu = _startMenu.Checked;
            _job.Launch = _launch.Checked;
            _cancel = false;
            SetBusy(true);
            Log.Write(string.Format("install: dir={0} zip={1} ver={2} desktop={3} startmenu={4} launch={5}",
                _job.Dir, _job.ZipPath == null ? "(сервер)" : _job.ZipPath, _job.Version,
                _job.Desktop, _job.StartMenu, _job.Launch));

            ProgressSink sink = new ProgressSink();
            sink.Status = delegate (string text) { Post(delegate { _status.Text = text; }); };
            sink.Fraction = delegate (double value) { Post(delegate { _progress.Value = value; }); };
            sink.Cancelled = delegate { return _cancel; };

            Thread worker = new Thread(delegate ()
            {
                try
                {
                    string summary = Installer.Install(_job, sink);
                    Post(delegate
                    {
                        Result = 0;
                        SetBusy(false);
                        _progress.Value = 1;
                        _status.Text = "Готово: " + summary;
                        _status.ForeColor = Theme.Ok;
                        _version.Text = "установлено";
                        _version.ForeColor = Theme.Ok;
                        MessageBox.Show(this,
                            "BLauncher " + _job.Version + " установлен в папку:\n" + _job.Dir + "\n\n" +
                            (_job.Desktop ? (_job.DesktopCreated ? "• Ярлык на рабочем столе создан\n" : "• Ярлык на рабочем столе создать не удалось\n") : "") +
                            (_job.StartMenu ? (_job.StartMenuCreated ? "• Ярлык в меню «Пуск» создан\n" : "• Ярлык в меню «Пуск» создать не удалось\n") : "") +
                            (_job.Launch ? "• Лаунчер запускается" : "• Лаунчер запустите ярлыком"),
                            "Установка завершена", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    });
                }
                catch (OperationCanceledException)
                {
                    Log.Write("install: отменено");
                    Post(delegate
                    {
                        Result = 2;
                        SetBusy(false);
                        _progress.Value = 0;
                        _status.ForeColor = Theme.Muted;
                        _status.Text = "Установка отменена.";
                        MessageBox.Show(this,
                            "Установка отменена.\n\nЕсли отмена пришлась на распаковку архива, папка осталась " +
                            "в промежуточном состоянии — запустите установку ещё раз, она доведёт обновление до конца.",
                            "Установка BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    });
                }
                catch (Exception ex)
                {
                    Log.Write("install: ошибка — " + ex);
                    string message = ex.Message;
                    Post(delegate
                    {
                        Result = 1;
                        SetBusy(false);
                        _progress.Value = 0;
                        _status.ForeColor = Theme.Danger;
                        _status.Text = "Ошибка: " + message;
                        MessageBox.Show(this,
                            "Не получилось установить лаунчер.\n\n" + message + "\n\n" +
                            "Можно скачать сборку вручную на blauncher.ru и указать её кнопкой «Из ZIP-файла…».\n" +
                            "Подробности: " + Log.Path,
                            "Ошибка установки", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    });
                }
            });
            worker.IsBackground = true;
            worker.Name = "install";
            worker.Start();
        }

        private void SetBusy(bool busy)
        {
            _busy = busy;
            _browse.Enabled = !busy;
            _fromZip.Enabled = !busy;
            _desktop.Enabled = !busy;
            _startMenu.Enabled = !busy;
            _launch.Enabled = !busy;
            _dir.ReadOnly = busy;

            // во время работы кнопка «Установить» превращается в «Отменить»
            _install.Text = busy ? "Отменить" : "Установить";
            _install.Kind = busy ? ButtonKind.Ghost : ButtonKind.Accent;
            _install.Invalidate();
            if (busy)
            {
                _status.ForeColor = Theme.Muted;
                _progress.Value = 0.01;
            }
        }

        private void InstallOrCancel(object sender, EventArgs e)
        {
            if (_busy)
            {
                _cancel = true;
                _status.ForeColor = Theme.Muted;
                _status.Text = "Останавливаюсь…";
                return;
            }
            Install();
        }

        private void Post(Action action)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(action); }
            catch (Exception ex) { Log.Write("post: " + ex.Message); }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (_busy)
            {
                _cancel = true;
                e.Cancel = true;
                _status.Text = "Останавливаюсь…";
                return;
            }
            base.OnFormClosing(e);
        }
    }

    // ------------------------------------------------------------------ окно удаления

    internal sealed class UninstallForm : Form
    {
        private readonly string _dir;
        private ToggleSwitch _data;
        private RoundedButton _remove;
        private RoundedButton _keep;
        private Label _status;
        private bool _busy;

        /// <summary>Код возврата процесса: 0 — удалено, 1 — не всё удалось.</summary>
        public int Result;

        public UninstallForm(string dir)
        {
            _dir = dir;

            Text = "Удаление BLauncher";
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.Font(9.75f);
            ClientSize = new Size(560, 320);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleDimensions = new SizeF(96f, 96f);
            AutoScaleMode = AutoScaleMode.Dpi;

            Label title = new Label();
            title.Text = "Удалить BLauncher?";
            title.Font = Theme.Font(17f, FontStyle.Bold);
            title.ForeColor = Theme.Text;
            title.BackColor = Color.Transparent;
            title.AutoSize = true;
            title.Location = new Point(24, 22);
            Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "Будут удалены файлы лаунчера, ярлыки и запись в списке программ.\n" +
                            "Папка: " + dir + "\n" +
                            "Игровые файлы (launcher_root) и настройки (config) останутся на месте.";
            subtitle.Font = Theme.Font(9.5f);
            subtitle.ForeColor = Theme.Muted;
            subtitle.BackColor = Color.Transparent;
            subtitle.AutoSize = false;
            subtitle.Size = new Size(ClientSize.Width - 48, 60);
            subtitle.Location = new Point(26, 62);
            Controls.Add(subtitle);

            RoundedPanel card = new RoundedPanel();
            card.Fill = Theme.Surface;
            card.BorderColor = Theme.Line;
            card.Location = new Point(24, 130);
            card.Size = new Size(ClientSize.Width - 48, 66);
            Controls.Add(card);

            _data = new ToggleSwitch();
            _data.Text = "Удалить и всё остальное: миры, настройки, вход";
            _data.BackColor = Theme.Surface;
            _data.Location = new Point(18, 19);
            _data.Size = new Size(card.Width - 36, 28);
            _data.Checked = false;
            card.Controls.Add(_data);

            _status = new Label();
            _status.Text = "";
            _status.Font = Theme.Font(9f);
            _status.ForeColor = Theme.Muted;
            _status.BackColor = Color.Transparent;
            _status.AutoSize = false;
            _status.Location = new Point(24, 208);
            _status.Size = new Size(ClientSize.Width - 48, 36);
            Controls.Add(_status);

            _keep = new RoundedButton();
            _keep.Text = "Отмена";
            _keep.Kind = ButtonKind.Ghost;
            _keep.Location = new Point(24, 256);
            _keep.Size = new Size(120, 40);
            _keep.Click += delegate { Close(); };
            Controls.Add(_keep);

            _remove = new RoundedButton();
            _remove.Text = "Удалить";
            _remove.Kind = ButtonKind.Danger;
            _remove.Location = new Point(ClientSize.Width - 24 - 170, 256);
            _remove.Size = new Size(170, 40);
            _remove.Click += delegate { Run(); };
            Controls.Add(_remove);
        }

        private void Run()
        {
            if (_busy) return;
            _busy = true;                       // дальше форму закрыть нельзя: увидит OnFormClosing
            _remove.Enabled = false;
            _keep.Enabled = false;
            _data.Enabled = false;
            _status.Text = "Удаляю…";
            bool removeData = _data.Checked;
            Log.Write("uninstall: dir=" + _dir + " data=" + removeData);

            ProgressSink sink = new ProgressSink();
            sink.Status = delegate (string text) { Post(delegate { _status.Text = text; }); };

            Thread worker = new Thread(delegate ()
            {
                try
                {
                    int failed = Installer.Uninstall(_dir, removeData, sink);
                    Post(delegate
                    {
                        Result = failed == 0 ? 0 : 1;
                        _status.Text = failed == 0
                            ? "Готово."
                            : "Готово, но " + failed + " объектов удалить не удалось (см. " + Log.Path + ").";
                        MessageBox.Show(this,
                            "BLauncher удалён." +
                            (removeData ? "" : "\nИгровые файлы и настройки оставлены.") +
                            (failed == 0 ? "" : "\n\nЧасть файлов удалить не удалось — подробности в " + Log.Path),
                            "Удаление завершено", MessageBoxButtons.OK, MessageBoxIcon.Information);
                        Application.Exit();
                    });
                }
                catch (Exception ex)
                {
                    Log.Write("uninstall: " + ex);
                    Post(delegate
                    {
                        Result = 1;
                        _busy = false;
                        _remove.Enabled = true;
                        _keep.Enabled = true;
                        _data.Enabled = true;
                        _status.ForeColor = Theme.Danger;
                        _status.Text = "Ошибка: " + ex.Message;
                    });
                }
            });
            worker.IsBackground = true;
            worker.Start();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Закрыть окно посреди удаления — значит оборвать поток на середине File.Delete.
            if (_busy) { e.Cancel = true; return; }
            base.OnFormClosing(e);
        }

        private void Post(Action action)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try { BeginInvoke(action); }
            catch (Exception ex) { Log.Write("post: " + ex.Message); }
        }
    }

    // ------------------------------------------------------------------ точка входа

    internal static class Program
    {
        // ZipArchive появился только в .NET 4.5: на голом 4.0 установщик падал бы уже после
        // скачивания 92 МБ. Тип трогаем в отдельном методе — если библиотеки нет, падает именно
        // его JIT, и это ловится в HasZipSupport.
        private static bool ZipTypeLoads() { return typeof(ZipArchive).FullName != null; }

        /// <summary>
        ///  Поддерживается ли эта Windows. Проверяется мажорная версия: 10 — это и Windows 10,
        ///  и Windows 11 (она тоже 10.x).
        ///
        ///  <para>Версия приходит настоящая, а не подменённая слоем совместимости, потому что
        ///  app.manifest объявляет supportedOS вплоть до Windows 10/11 — без этого
        ///  Environment.OSVersion отдавал бы 6.2 на любой новой системе. Подтверждено данными
        ///  отчёта: у настоящих пользователей там 10.0.19045, 10.0.26200 и подобное.</para>
        /// </summary>
        private static bool SupportedWindows()
        {
            try { return Environment.OSVersion.Version.Major >= 10; }
            catch { return true; }   // не смогли определить — не мешаем человеку
        }

        private static string OsVersionText()
        {
            try { return Environment.OSVersion.Version.ToString(); }
            catch { return "неизвестна"; }
        }

        private static bool HasZipSupport()
        {
            try { return ZipTypeLoads(); }
            catch (Exception ex)
            {
                Log.Write("нет System.IO.Compression.ZipArchive — " + ex.GetType().Name);
                return false;
            }
        }

        [STAThread]
        internal static int Main(string[] args)
        {
            SetupOptions options = SetupOptions.Parse(args);
            Net.InitTls();
            if (options.NoStats) Stats.Disable();
            Log.Write("старт: " + string.Join(" ", args));

            if (options.Help)
            {
                MessageBox.Show(SetupOptions.HelpText(), "BLauncherSetup", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }

            if (!HasZipSupport())
            {
                if (!options.Silent)
                    MessageBox.Show(
                        "Для распаковки сборки нужен .NET Framework 4.5 или новее.\n\n" +
                        "Установите его с сайта Microsoft и запустите установщик снова.",
                        "Установка BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Log.Write("нет System.IO.Compression.ZipArchive — нужен .NET Framework 4.5+");
                return 1;
            }

            // Маленький exe без вшитой Java — это uninstall.exe из папки установки. 9 октября игрок
            // запустил его двойным щелчком, без /uninstall: открылось окно УСТАНОВКИ, а на
            // распаковке Java оно сразу упало («во встроенной части нет данных», в отчёте —
            // голый IOException за 34 мс). Человек хотел удалить лаунчер, поэтому без ключа такой
            // exe ведёт себя как программа удаления той папки, где лежит. Окно удаления само
            // спрашивает подтверждение. Из /ZIP= полная сборка ставится и им — её не трогаем.
            if (!options.Uninstall && string.IsNullOrEmpty(options.Zip) && Installer.EmbeddedSize() == 0)
            {
                string self = null;
                try { self = Path.GetDirectoryName(Path.GetFullPath(Application.ExecutablePath)); } catch { }
                if (!string.IsNullOrEmpty(self) && Installer.LooksLikeInstall(self))
                {
                    Log.Write("запущен uninstall.exe без ключа — открываю удаление " + self);
                    options.Uninstall = true;
                    options.Dir = self;
                }
                else
                {
                    if (!options.Silent)
                        MessageBox.Show(
                            "Это программа удаления BLauncher, а не установщик: внутри нет файлов лаунчера.\n\n" +
                            "Скачайте установщик с сайта blauncher.ru.",
                            "Установка BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    Log.Write("отказ: во встроенной части нет Java — это uninstall.exe не из папки установки");
                    return 1;
                }
            }

            // Windows старше 10 отсекается СРАЗУ, до загрузки чего-либо.
            //
            // Внутри установщика Java 21, а она на Windows 7 и 8 не работает — значит установка
            // не может закончиться ничем полезным ни при каких условиях. Раньше человек на
            // Windows 7 скачивал 55 МБ, запускал и получал невнятную ошибку ввода-вывода на
            // распаковке. Это видно в отчёте: все обращения с os 6.1.7601 падали на стадии
            // java_extract.
            //
            // Удаление пропускаем через проверку: снять уже стоящий лаунчер надо дать на любой
            // системе, иначе человек останется с папкой, которую нечем убрать.
            if (!options.Uninstall && !SupportedWindows())
            {
                string message =
                    "BLauncher работает на Windows 10 и новее.\n\n"
                    + "Внутри установщика Java 21, а она на Windows 7 и 8 не запускается, поэтому "
                    + "установка не смогла бы завершиться.\n\n"
                    + "Ваша версия Windows: " + OsVersionText();
                if (!options.Silent)
                    MessageBox.Show(message, "Установка BLauncher",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                Log.Write("отказ: Windows " + OsVersionText() + " не поддерживается (нужна 10+)");
                return 1;
            }

            if (options.Uninstall)
            {
                string dir = options.Dir;
                if (string.IsNullOrEmpty(dir) || dir == Installer.DefaultDir())
                {
                    string known = Installer.InstalledDirFromRegistry();
                    if (!string.IsNullOrEmpty(known)) dir = known;
                }
                string raw = options.Dir == null ? "" : options.Dir.Trim();
                string bare = raw.TrimEnd('\\', '/');
                if (bare.Length == 0 || (bare.Length == 2 && bare[1] == ':') ||
                    string.Equals(Path.GetFullPath(raw).TrimEnd('\\'),
                                  Path.GetPathRoot(Path.GetFullPath(raw)).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                {
                    // «C:» и «C:\» — это не папка установки: GetFullPath развернул бы их в текущий каталог.
                    if (!options.Silent)
                        MessageBox.Show("Не понял, что удалять: «" + raw + "» — это диск, а не папка лаунчера.",
                            "Удаление BLauncher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    Log.Write("uninstall: отвергаю путь " + raw);
                    return 1;
                }
                try { dir = Path.GetFullPath(raw).TrimEnd('\\'); } catch { }

                if (!Directory.Exists(dir))
                {
                    if (!options.Silent)
                        MessageBox.Show("Не нашёл установленный BLauncher (" + dir + ").", "Удаление BLauncher",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    Log.Write("uninstall: папка не найдена — " + dir);
                    return 1;
                }

                // Папка должна быть похожа на установку: иначе «удаление» снесло бы чужие файлы.
                if (!Installer.LooksLikeInstall(dir))
                {
                    if (options.Silent)
                    {
                        // В тихом режиме спрашивать некого — молча ничего не удаляем.
                        Log.Write("uninstall: в папке нет файлов BLauncher, ничего не удаляю — " + dir);
                        return 1;
                    }
                    DialogResult answer = MessageBox.Show(
                        "В папке " + dir + " нет файлов BLauncher.\n\n" +
                        "Продолжать опасно: будут удалены все файлы и папки в ней (кроме launcher_root и config).\n" +
                        "Удалить всё равно?",
                        "Удаление BLauncher", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                    if (answer != DialogResult.Yes) return 2;
                }

                if (options.Silent)
                {
                    try
                    {
                        ProgressSink quiet = new ProgressSink();
                        return Installer.Uninstall(dir, options.RemoveData, quiet) == 0 ? 0 : 1;
                    }
                    catch (Exception ex)
                    {
                        Log.Write("silent uninstall: " + ex);
                        return 1;
                    }
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                UninstallForm form = new UninstallForm(dir);
                Application.Run(form);
                return form.Result;
            }

            if (options.Silent)
            {
                InstallJob job = new InstallJob();
                job.ZipPath = options.Zip;
                job.Desktop = options.Desktop;
                job.StartMenu = options.StartMenu;
                job.Launch = options.Launch;

                string problem = Installer.ValidateTargetDir(options.Dir);
                if (problem != null)
                {
                    Log.Write("silent install: негодная папка " + options.Dir + " — " + problem);
                    return 1;
                }
                try { job.Dir = Path.GetFullPath(options.Dir).TrimEnd('\\'); } catch { job.Dir = options.Dir; }

                try
                {
                    // Локальный архив — значит к серверу не ходим вовсе (и версию берём вшитую).
                    if (string.IsNullOrEmpty(job.ZipPath))
                    {
                        RemoteInfo info = Installer.Probe();
                        job.Version = info.Version;
                        job.ZipUrl = info.ZipUrl;
                        job.ZipSha256 = info.ZipSha256;
                        job.LauncherUrl = info.LauncherUrl;
                        job.LauncherSha256 = info.LauncherSha256;
                        job.LauncherSize = info.LauncherSize;
                        job.ZipSize = info.ZipSize;
                    }
                    else
                    {
                        job.Version = BuildInfo.DefaultVersion;
                    }

                    ProgressSink quiet = new ProgressSink();
                    Installer.Install(job, quiet);
                    return 0;
                }
                catch (OperationCanceledException) { return 2; }
                catch (Exception ex)
                {
                    Log.Write("silent install: " + ex);
                    return 1;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            SetupForm setup = new SetupForm(options);
            Application.Run(setup);
            return setup.Result;
        }
    }
}
