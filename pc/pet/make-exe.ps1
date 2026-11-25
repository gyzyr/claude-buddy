# Makes node_modules\electron\dist\Claude Buddy.exe: a copy of electron.exe whose
# version info says "Claude Buddy", so Task Manager shows that name instead of "Electron".
param([string]$Name = "Claude Buddy")
$ErrorActionPreference = "Stop"
$dist = Join-Path $PSScriptRoot "node_modules\electron\dist"
$src = Join-Path $dist "electron.exe"
$dst = Join-Path $dist "$Name.exe"
if (-not (Test-Path $src)) { throw "electron.exe not found - run npm install in pc\pet first." }

Add-Type -TypeDefinition @"
using System; using System.Collections.Generic; using System.IO; using System.Text; using System.Runtime.InteropServices;
public static class VerRes {
  [DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode)] static extern IntPtr LoadLibraryEx(string f, IntPtr h, uint fl);
  [DllImport("kernel32")] static extern bool FreeLibrary(IntPtr h);
  [DllImport("kernel32")] static extern IntPtr FindResourceEx(IntPtr h, IntPtr type, IntPtr name, ushort lang);
  [DllImport("kernel32")] static extern IntPtr LoadResource(IntPtr h, IntPtr r);
  [DllImport("kernel32")] static extern IntPtr LockResource(IntPtr r);
  [DllImport("kernel32")] static extern uint SizeofResource(IntPtr h, IntPtr r);
  delegate bool LangProc(IntPtr h, IntPtr t, IntPtr n, ushort lang, IntPtr p);
  [DllImport("kernel32")] static extern bool EnumResourceLanguages(IntPtr h, IntPtr t, IntPtr n, LangProc f, IntPtr p);
  [DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode)] static extern IntPtr BeginUpdateResource(string f, bool del);
  [DllImport("kernel32", SetLastError=true)] static extern bool UpdateResource(IntPtr h, IntPtr t, IntPtr n, ushort lang, byte[] d, uint len);
  [DllImport("kernel32", SetLastError=true)] static extern bool EndUpdateResource(IntPtr h, bool discard);

  class Node { public string Key; public ushort Type; public ushort ValLen; public byte[] Val; public List<Node> Kids = new List<Node>(); }
  static int A4(int x) { return (x + 3) & ~3; }
  static Node Parse(byte[] b, int at) {
    int len = BitConverter.ToUInt16(b, at); var n = new Node();
    n.ValLen = BitConverter.ToUInt16(b, at + 2); n.Type = BitConverter.ToUInt16(b, at + 4);
    int p = at + 6, k = p; while (BitConverter.ToUInt16(b, p) != 0) p += 2;
    n.Key = Encoding.Unicode.GetString(b, k, p - k); p = A4(p + 2);
    int vb = n.Type == 1 ? n.ValLen * 2 : n.ValLen; n.Val = new byte[vb]; Array.Copy(b, p, n.Val, 0, vb); p = A4(p + vb);
    while (p < at + len) { var c = Parse(b, p); n.Kids.Add(c); p = A4(p + BitConverter.ToUInt16(b, p)); }
    return n;
  }
  static void Write(MemoryStream m, Node n) {
    int start = (int)m.Length; var w = new BinaryWriter(m);
    w.Write((ushort)0); w.Write(n.ValLen); w.Write(n.Type); w.Write(Encoding.Unicode.GetBytes(n.Key + "\0"));
    while (m.Length % 4 != 0) w.Write((byte)0);
    w.Write(n.Val);
    foreach (var c in n.Kids) { while (m.Length % 4 != 0) w.Write((byte)0); Write(m, c); }
    long end = m.Length; m.Position = start; w.Write((ushort)(end - start)); m.Position = end;
  }
  static void Rename(Node n, string name) {
    if (n.Type == 1 && (n.Key == "FileDescription" || n.Key == "ProductName")) {
      n.Val = Encoding.Unicode.GetBytes(name + "\0"); n.ValLen = (ushort)(name.Length + 1);
    }
    foreach (var c in n.Kids) Rename(c, name);
  }
  public static void Apply(string src, string dst, string name) {
    IntPtr h = LoadLibraryEx(src, IntPtr.Zero, 2);
    if (h == IntPtr.Zero) throw new Exception("LoadLibraryEx failed: " + Marshal.GetLastWin32Error());
    ushort lang = 1033; EnumResourceLanguages(h, (IntPtr)16, (IntPtr)1, (a, t, nm, l, p) => { lang = l; return false; }, IntPtr.Zero);
    IntPtr r = FindResourceEx(h, (IntPtr)16, (IntPtr)1, lang);
    if (r == IntPtr.Zero) { FreeLibrary(h); throw new Exception("no version resource"); }
    var data = new byte[SizeofResource(h, r)]; Marshal.Copy(LockResource(LoadResource(h, r)), data, 0, data.Length);
    FreeLibrary(h);
    var root = Parse(data, 0); Rename(root, name);
    var m = new MemoryStream(); Write(m, root); var outb = m.ToArray();
    IntPtr u = BeginUpdateResource(dst, false);
    if (u == IntPtr.Zero) throw new Exception("BeginUpdateResource failed: " + Marshal.GetLastWin32Error());
    if (!UpdateResource(u, (IntPtr)16, (IntPtr)1, lang, outb, (uint)outb.Length)) { EndUpdateResource(u, true); throw new Exception("UpdateResource failed"); }
    if (!EndUpdateResource(u, false)) throw new Exception("EndUpdateResource failed: " + Marshal.GetLastWin32Error());
  }
}
"@

Copy-Item $src $dst -Force
[VerRes]::Apply($src, $dst, $Name)
$v = (Get-Item $dst).VersionInfo
Write-Host "Made $dst ($($v.FileDescription))"
