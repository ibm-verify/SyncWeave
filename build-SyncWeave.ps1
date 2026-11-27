# Copyright contributors to the SyncWeave project
#
# SPDX-License-Identifier: Apache-2.0

<#
.SYNOPSIS
    Build SyncWeave inside an AlmaLinux 9 WSL distro from Windows.

.DESCRIPTION
    AlmaLinux 9 is a 1:1 RHEL 9 rebuild - the same platform as the RHEL 9
    build host described in docs/syncweave_local_build_steps.md. It avoids the
    ct.sym / JDK packaging issues present in Ubuntu's OpenJDK packages.

    On first run this script:
      1. Checks that the AlmaLinux9 WSL distro exists and gives instructions
         if it does not (it must be imported manually - see SETUP below).
      2. Installs required packages inside the distro (ant, python3, libxslt,
         dos2unix) via dnf - idempotent, skips already-installed packages.
      3. Copies ivy/ivy.jar into ANT_HOME/lib (idempotent).

    NOTE: The RHEL/EPEL-packaged ant wrapper runs in "rpm_mode" by default,
    which uses /usr/bin/build-classpath to build the Ant classpath and does
    NOT scan ANT_HOME/lib for extra jars.  This prevents ivy.jar from being
    found even when correctly placed in ANT_HOME/lib.  All ant invocations
    in this script pass --noconfig to disable rpm_mode and use the standard
    Ant launcher classpath scan of ANT_HOME/lib.

    On every run it then:
      4. Runs "ant resolve rename_jars" unless lib/ivy/ is already populated
         or -SkipResolve is set.
      5. Runs "ant <Target>" (default: package) and tees output to build.log.
      6. Reports BUILD SUCCESSFUL / BUILD FAILED with colour and output paths.

    All bundled tools (JDK 21, Eclipse PDE 4.39.0) are read directly from
    the repo via the WSL /mnt/ mount - nothing is copied into the distro.

    The script lives in the repository root and builds the clone it sits in.
    Run it from a Windows clone, for example:
      C:\Users\you\sb\vdi-syncweave\
        build-SyncWeave.ps1   <- here
        build.xml

    Prerequisite: the bundled JDK must already be downloaded into
    adks/ibm/jdk/ by setup-jdk.sh (run it once inside the distro, see
    docs/syncweave_local_build_steps.md, "Building on Windows").

  -- ONE-TIME SETUP ------------------------------------------------------------

    AlmaLinux 9 cannot be installed via "wsl --install" (not in the Store).
    Run these commands once in PowerShell before using this script:

    Running this script before the distro exists prints these commands with
    the exact, tested image URL filled in ($AlmaImageUrl near the top of the
    script body).  Releases: https://github.com/AlmaLinux/wsl-images/releases

    # 1. Download the AlmaLinux 9 WSL image (~200 MB)
    Invoke-WebRequest -Uri "<AlmaLinux 9 .wsl image URL>" `
      -OutFile "$env:TEMP\AlmaLinux9.wsl" -UseBasicParsing

    # 2. Create a directory for the virtual disk (alongside other WSL distros)
    New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\WSL\AlmaLinux9"

    # 3. Import into WSL
    wsl --import AlmaLinux9 "$env:LOCALAPPDATA\WSL\AlmaLinux9" "$env:TEMP\AlmaLinux9.wsl"

    # 4. Verify
    wsl -d AlmaLinux9 -- cat /etc/redhat-release
    # Expected: AlmaLinux release 9.x

  -----------------------------------------------------------------------------

.PARAMETER RepoPath
    Path to the SyncWeave repository root.
    Defaults to the directory containing this script (the repository root).
    Override with: -RepoPath C:\other\location\vdi-syncweave

.PARAMETER Target
    Ant target to run.  Allowed values: package (default) | images.
    - package : compile + OSGi bundles + CE RCP zips.  Faster, no javadoc.
    - images  : everything in package plus javadoc, installer zips, ship/ tree.

.PARAMETER JavaHome
    JDK to build with, as a Windows path (absolute, or relative to the repo
    root).  It must be a Linux JDK, because the build runs inside WSL.
    Default: auto-detected under adks\ibm\jdk\ (where setup-jdk.sh puts it).
    The script picks the jdk-* directory with the highest version, so a JDK
    bump in setup-jdk.sh needs no change here.
    Example: -JavaHome adks\ibm\jdk\jdk-21.0.13+11

.PARAMETER DistroName
    Name of the WSL distro to use.  Default: AlmaLinux9.
    Must already be imported (see SETUP above).

.PARAMETER SkipResolve
    Force-skip "ant resolve rename_jars" even when lib/ivy/ is empty.
    Useful when you have manually placed jars there.

.PARAMETER SkipIvySetup
    Skip copying ivy.jar into ANT_HOME/lib.
    Use after the first successful run on a given machine.
    ANT_HOME/lib lives inside the distro, so do not use -SkipIvySetup on a
    fresh or re-imported distro.

.PARAMETER NativeFs
    Build on the WSL2 native Linux filesystem.  This is the DEFAULT; the
    switch is kept so existing command lines still work.  Use -MountedFs to
    build over /mnt/c instead.

    The repo is rsynced from the Windows clone into ~/sb/SyncWeave inside the
    distro before the build runs.  All I/O (Dojo minification, OSGi staging,
    PDE headless build) happens on native ext4 - typically 3-4x faster than
    building over /mnt/c.

    After the build, export/ (and ship/ for -Target images) is rsynced back to
    the Windows clone so outputs are accessible at their normal Windows paths.
    build.log is also copied back regardless of build outcome.

    lib/ivy/ is included in the sync so a prior resolve is reused and
    "ant resolve" does not need to re-run.

    rsync is installed automatically in the distro alongside the other packages.

.PARAMETER MountedFs
    Build directly over the /mnt/c DrvFs mount instead of the native ext4 copy.
    Much slower (a full build can take well over an hour, against about
    8 minutes on ext4), but nothing is copied into the distro.
    Cannot be combined with -NativeFs.

.PARAMETER SkipSync
    Skip the rsync-in step (native filesystem mode).

    Use this after the first successful build when you have not changed
    any source files and just want to re-run the build on the already-synced
    Linux copy.  The Linux repo at ~/sb/SyncWeave is used as-is.

    Has no effect with -MountedFs.

.PARAMETER SkipSyncBack
    Skip syncing export/ back to the Windows repo after the build.

    Use this when you want to consume build outputs directly from the WSL2
    native filesystem via the UNC path instead of waiting for the sync:

        \\wsl$\AlmaLinux9\root\sb\SyncWeave\export\

    build.log is still copied back regardless of this switch.
    Has no effect with -MountedFs.

.PARAMETER BuildTests
    After a successful main build, compile the unit-test JARs and produce
    the deployable zip by running "ant package_unit_tests zip_unit_tests".
    Outputs:
      - export/unit_tests/test.jar, boot.jar, test_DummyConnector.jar
      - ship/tests/unit_tests.zip  (CRLF-fixed, ready to unzip into an install)
    The build log is written to unit_tests_build.log in the repo root.
    Has no effect when the main build fails.

.PARAMETER RunTests
    After a successful main build, compile the unit-test JARs and zip
    (implies -BuildTests), unzip unit_tests.zip into a product install,
    stage the runtime JARs, run the JUnit suite via runTestSuite.sh,
    and report pass/fail.
    Logs: unit_tests_build.log and test_run.log in the repo root.
    Has no effect when the main build fails.

    By default the product install is created from this build: the target is
    forced to images, ship/install/SyncWeave-*-linux-x86_64.tgz is unpacked
    and installed with its install_server.sh into /root/sb/test_install
    inside the distro (recreated on every run).  This needs the Linux JRE in
    adks/ibm/jre/ (downloaded by setup-jdk.sh).

.PARAMETER TestInstallRoot
    Optional.  Run the unit tests against an existing product install instead
    of a fresh one, as a WSL path (/opt/...) or a Windows path (C:\...),
    which is converted to /mnt/c/...  Must already contain
    bin/setupCmdLine.sh (i.e. a Linux product install - a Windows install,
    which only has setupCmdLine.bat, cannot be used).
    Example: -TestInstallRoot /opt/IBM/TDI/V7.1
    Has no effect without -RunTests.

.PARAMETER Clean
    Delete export/ before building, like "ant clean" (and "ant developer" when
    combined with the default package target).  Done with rm -rf rather than
    "ant clean" because Ant cannot delete the deep dojox tree over /mnt/c
    (MAX_PATH).  By default the Linux copy's export/ is cleaned, and the
    sync-back then mirrors the result to the Windows clone.

.EXAMPLE
    # First-time build (run from the repository root)
    .\build-SyncWeave.ps1

.EXAMPLE
    # Explicit repo path
    .\build-SyncWeave.ps1 -RepoPath C:\work\vdi-syncweave

.EXAMPLE
    # Full release build
    .\build-SyncWeave.ps1 -Target images

.EXAMPLE
    # Use a custom distro name
    .\build-SyncWeave.ps1 -DistroName MyAlma9 -Target package

.EXAMPLE
    # Clean build (same as "ant clean package")
    .\build-SyncWeave.ps1 -Clean

.EXAMPLE
    # Fastest mode - no sync back, access outputs via UNC path
    .\build-SyncWeave.ps1 -SkipSyncBack

.EXAMPLE
    # Build over /mnt/c instead of the ext4 copy (much slower)
    .\build-SyncWeave.ps1 -MountedFs

.EXAMPLE
    # Build product + compile unit-test JARs (no test run)
    .\build-SyncWeave.ps1 -BuildTests

.EXAMPLE
    # Build product + compile and run the full unit-test suite
    .\build-SyncWeave.ps1 -RunTests

#>

#Requires -Version 5.1

[CmdletBinding()]
param(
    [string] $RepoPath = $PSScriptRoot,

    [ValidateSet('package', 'images')]
    [string] $Target = 'package',

    [string] $DistroName = 'AlmaLinux9',

    [string] $JavaHome = '',

    [switch] $Clean,

    [switch] $SkipResolve,

    [switch] $SkipIvySetup,

    [switch] $NativeFs,

    [switch] $MountedFs,

    [switch] $SkipSync,

    [switch] $SkipSyncBack,

    # Compile unit-test JARs after a successful main build; no test execution.
    [switch] $BuildTests,

    # Compile unit-test JARs AND run the full JUnit suite (implies -BuildTests).
    [switch] $RunTests,

    [string] $TestInstallRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Tested AlmaLinux 9 WSL image, printed in the setup instructions below.
$AlmaImageUrl = 'https://github.com/AlmaLinux/wsl-images/releases/download/v9.8.20260526.0/AlmaLinux-9.8_x64_20260526.0.wsl'

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Write-Step([string] $Message) {
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string] $Message) {
    Write-Host "    [OK] $Message" -ForegroundColor Green
}

function Write-Skip([string] $Message) {
    Write-Host "    [--] $Message" -ForegroundColor DarkGray
}

function Write-Warn([string] $Message) {
    Write-Host "    [!!] $Message" -ForegroundColor Yellow
}

# Print an error and stop with exit code 1.  (Write-Error would throw a raw
# error record here because $ErrorActionPreference is 'Stop'.)
function Stop-Build([string] $Message) {
    Write-Host ""
    Write-Host "  ERROR: $Message" -ForegroundColor Red
    Write-Host ""
    exit 1
}

# Native ext4 build is the default; -MountedFs opts out.
if ($NativeFs -and $MountedFs) { Stop-Build "-NativeFs and -MountedFs cannot be used together." }
$NativeFs = [switch](-not $MountedFs)

# ---------------------------------------------------------------------------
# WSL interop note:
#   By default WSL appends the Windows %PATH% to $PATH inside every bash
#   session.  Windows paths contain spaces (e.g. "C:\Program Files\..."),
#   which are illegal bash identifier characters.  Any `export PATH=...`
#   that inherits this value causes:
#       bash: export: `Files/...': not a valid identifier
#
#   Fix: every wsl call uses `env -i PATH=<safe> HOME=/root bash --noprofile --norc`.
#   - env -i   : starts bash with an empty environment (no inherited Windows vars)
#   - --noprofile --norc : skips /etc/profile.d/ scripts, which on RHEL/AlmaLinux
#                          re-inject the WSL interop PATH even under env -i
#   This does NOT modify wsl.conf or affect other WSL sessions.
# ---------------------------------------------------------------------------

# A clean, minimal Linux PATH used as the starting point for all wsl calls.
# Covers: system binaries, sbin, dnf/rpm, ant (installed to /usr/bin).
$WslCleanPath = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'

# Run a command inside the WSL distro as root.  Streams output live.
# Returns the exit code.  Throws on non-zero unless -AllowFail is set.
# -Quiet discards the command's output inside bash, so that callers doing
# "$rc = Invoke-Wsl ... -Quiet" get only the exit code back.  (Without it,
# stdout such as "/usr/bin/ant" from "which ant" would become part of the
# return value and "$rc -ne 0" would be true even on success.)
# NOTE: wsl.exe passes the command line through the distro's default shell
# before bash -c runs it, so any $variable in $Command is expanded early
# (usually to empty).  Keep $ out of these commands; logic that needs shell
# variables belongs in the generated build script ($scriptLines).
function Invoke-Wsl {
    param(
        [Parameter(Mandatory)][string] $Command,
        [switch] $AllowFail,
        [switch] $Quiet
    )
    if ($Quiet) { $Command = "{ $Command ; } >/dev/null 2>&1" }
    Write-Verbose "    wsl -d $DistroName -u root -- env -i PATH=$WslCleanPath HOME=/root bash --noprofile --norc -c '$Command'"
    # env -i starts bash with an empty environment (no inherited Windows PATH).
    # --noprofile --norc prevents WSL /etc/profile.d/ scripts from re-injecting
    # the Windows interop PATH (which contains spaces and breaks bash export).
    # We restore HOME so tools like ant can find ~/.ant/lib if needed.
    # -u root: the script installs packages and builds under /root, so it must
    # not depend on the distro's default user.
    & wsl -d $DistroName -u root -- env '-i' "PATH=$WslCleanPath" 'HOME=/root' bash --noprofile --norc -c $Command
    $rc = $LASTEXITCODE
    if ($rc -ne 0 -and -not $AllowFail) {
        throw "WSL command failed (exit $rc): $Command"
    }
    return $rc
}

# Run a command inside WSL and capture its stdout as a trimmed string.
# Uses the same env -i / clean-PATH / --noprofile --norc isolation as Invoke-Wsl.
function Get-WslOutput([string] $Command) {
    $out = & wsl -d $DistroName -u root -- env '-i' "PATH=$WslCleanPath" 'HOME=/root' bash --noprofile --norc -c "{ $Command ; } 2>/dev/null"
    return ($out -join '').Trim()
}

# Convert a Windows path to its /mnt/... WSL equivalent.
function ConvertTo-WslPath([string] $WinPath) {
    $p = $WinPath.Replace('\', '/')
    if ($p -match '^([A-Za-z]):(.*)') {
        $drive = $Matches[1].ToLower()
        $rest  = $Matches[2]
        return "/mnt/$drive$rest"
    }
    return $p
}

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------

Write-Step "Pre-flight checks"

# wsl.exe must exist
if (-not (Get-Command 'wsl.exe' -ErrorAction SilentlyContinue)) {
    Stop-Build ("wsl.exe not found on PATH.`n" +
        "WSL is not enabled or not installed.`n" +
        "Enable it with: wsl --install`n" +
        "Requires Windows 10 build 19041+ or Windows 11.")
}

# -RunTests without -TestInstallRoot installs this build into the distro,
# which needs the images target (it produces the Linux installer).
$AutoInstall = $RunTests -and -not $TestInstallRoot
if ($AutoInstall -and $Target -ne 'images') {
    Write-Warn "-RunTests installs this build to test it, so the target is switched from '$Target' to 'images'."
    $Target = 'images'
}
# Accept a Windows path: check it here and hand bash its /mnt/<drive> form.
if ($RunTests -and $TestInstallRoot -match '^[A-Za-z]:') {
    if (-not (Test-Path (Join-Path $TestInstallRoot 'bin\setupCmdLine.sh'))) {
        Stop-Build ("-TestInstallRoot '$TestInstallRoot' has no bin\setupCmdLine.sh.`n" +
            "It must be a Linux product install (a Windows install only has setupCmdLine.bat).")
    }
    $TestInstallRoot = ConvertTo-WslPath $TestInstallRoot
}
if ($TestInstallRoot) { $TestInstallRoot = $TestInstallRoot.TrimEnd('/') }
$TestInstallDir = '/root/sb/test_install'

# Resolve and validate the repo root
$resolved = Resolve-Path $RepoPath -ErrorAction SilentlyContinue
$repoRoot = if ($resolved) { $resolved.Path } else { $null }
if (-not $repoRoot -or -not (Test-Path (Join-Path $repoRoot 'build.xml'))) {
    Stop-Build ("SyncWeave repository not found at: $RepoPath`n" +
        "Pass the correct path with: -RepoPath <path-to-SyncWeave>")
}

$wslRepoRoot  = ConvertTo-WslPath $repoRoot
$wslToolsHome = "$wslRepoRoot/tools"
$wslIvySrc    = "$wslRepoRoot/ivy/ivy.jar"

Write-Ok "Repository root : $repoRoot"
Write-Ok "WSL mount path  : $wslRepoRoot"

# ---------------------------------------------------------------------------
# Locate the JDK
# ---------------------------------------------------------------------------
# The bundled JDK is not in git; setup-jdk.sh downloads it into adks/ibm/jdk/.
# The version is not hardcoded: pick the highest jdk-* directory there (an old
# one can linger after a JDK bump), or use -JavaHome.

$jdkSetupHint = ("Download it once from inside the distro, for example:`n" +
    "  wsl -d $DistroName --cd `"$repoRoot`" -- bash -c 'dnf install -y wget tar gzip && bash <(tr -d \\r < setup-jdk.sh)'")

# Sort key for names like jdk-21.0.12+8: numeric parts compared as numbers.
function Get-JdkSortKey([string] $Name) {
    $nums = [regex]::Matches($Name, '\d+') | ForEach-Object { '{0:D6}' -f [int]$_.Value }
    return ($nums -join '.')
}

if ($JavaHome) {
    $jdkPath = if ([System.IO.Path]::IsPathRooted($JavaHome)) { $JavaHome } else { Join-Path $repoRoot $JavaHome }
    $jdkResolved = Resolve-Path $jdkPath -ErrorAction SilentlyContinue
    if (-not $jdkResolved -or -not (Test-Path (Join-Path $jdkResolved.Path 'bin\java'))) {
        Stop-Build "-JavaHome '$JavaHome' is not a Linux JDK (bin/java not found under $jdkPath)."
    }
    $jdkWinPath = $jdkResolved.Path
} else {
    $jdkRoot = Join-Path $repoRoot 'adks\ibm\jdk'
    $jdkDirs = @(Get-ChildItem -Path $jdkRoot -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java') } |
        Sort-Object { Get-JdkSortKey $_.Name } -Descending)
    if ($jdkDirs.Count -eq 0) {
        Stop-Build ("No JDK found under adks\ibm\jdk\ (expected a jdk-* directory with bin/java).`n" +
            $jdkSetupHint + "`nOr point at a Linux JDK with -JavaHome <path>.")
    }
    if ($jdkDirs.Count -gt 1) {
        Write-Warn ("Several JDKs under adks\ibm\jdk\: " + (($jdkDirs | ForEach-Object Name) -join ', ') +
            ". Using the newest, $($jdkDirs[0].Name). Pass -JavaHome to choose another.")
    }
    $jdkWinPath = $jdkDirs[0].FullName
}

# Keep the JDK path relative to the repo when it is inside it, so -NativeFs
# can repoint it at the Linux copy.
$repoPrefix = $repoRoot.TrimEnd('\') + '\'
$jdkRelPath = if ($jdkWinPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    $jdkWinPath.Substring($repoPrefix.Length).Replace('\', '/')
} else { $null }
$wslJavaHome = if ($jdkRelPath) { "$wslRepoRoot/$jdkRelPath" } else { ConvertTo-WslPath $jdkWinPath }
Write-Ok "JAVA_HOME       : $wslJavaHome"

# The Linux installer bundles the Linux JRE from adks/ibm/jre (setup-jdk.sh).
if ($AutoInstall -and -not (Test-Path (Join-Path $repoRoot 'adks\ibm\jre\ibm-semeru-open-jre_x64_linux_*'))) {
    Stop-Build ("-RunTests needs the Linux JRE in adks\ibm\jre\ to build the installer, and it is missing.`n" +
        $jdkSetupHint + "`nOr pass -TestInstallRoot to test against an existing Linux install.")
}

# Clean local Eclipse/IDE compilation output directories to avoid PDE build conflicts
Write-Step "Cleaning local IDE compilation directories (bin/ folders)"
Get-ChildItem -Path (Join-Path $repoRoot 'osgi\plugins') -Filter bin -Directory -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
}
Get-ChildItem -Path (Join-Path $repoRoot 'ce_rcp\plugins') -Filter bin -Directory -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
    Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Ok "Stale compilation directories cleaned"

# ---------------------------------------------------------------------------
# Distro presence check (AlmaLinux9 must be imported manually)
# ---------------------------------------------------------------------------

Write-Step "Checking WSL distro: $DistroName"

$nonPrintable = '[^ -~]'
function Remove-NonPrintable([string] $s) { $s -replace $nonPrintable, '' }

$wslListRaw  = & wsl --list --quiet 2>&1
$distroNames = $wslListRaw |
    ForEach-Object { Remove-NonPrintable $_ } |
    Where-Object   { $_.Trim() -ne '' }

$distroPresent = $distroNames | Where-Object { $_.Trim() -ieq $DistroName }

if (-not $distroPresent) {
    Write-Host ""
    Write-Host "  ERROR: WSL distro '$DistroName' not found." -ForegroundColor Red
    Write-Host ""
    Write-Host "  AlmaLinux 9 must be imported manually. Run these commands once:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host '  Invoke-WebRequest `' -ForegroundColor White
    Write-Host "    -Uri `"$AlmaImageUrl`" ``" -ForegroundColor White
    Write-Host '    -OutFile "$env:TEMP\AlmaLinux9.wsl" -UseBasicParsing' -ForegroundColor White
    Write-Host '  New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\WSL\AlmaLinux9"' -ForegroundColor White
    Write-Host '  wsl --import AlmaLinux9 "$env:LOCALAPPDATA\WSL\AlmaLinux9" "$env:TEMP\AlmaLinux9.wsl"' -ForegroundColor White
    Write-Host ""
    exit 1
}

Write-Ok "$DistroName present"

# Verify it is actually RHEL/AlmaLinux
$releaseStr = Get-WslOutput 'cat /etc/redhat-release 2>/dev/null || echo unknown'
Write-Ok "Distro: $releaseStr"

# ---------------------------------------------------------------------------
# Install missing packages via dnf
# ---------------------------------------------------------------------------

Write-Step "Checking required packages"

# Ant on RHEL 9 requires EPEL
$epelInstalled = Invoke-Wsl -Command "rpm -q epel-release" -AllowFail -Quiet
if ($epelInstalled -ne 0) {
    Write-Warn "Installing EPEL repository..."
    Invoke-Wsl -Command "dnf install -y epel-release"
    Write-Ok "EPEL installed"
} else {
    Write-Skip "epel-release already installed"
}

# Packages: binary tools checked via 'which', libraries via 'rpm -q'
# rsync is always installed - needed for -NativeFs, harmless otherwise.
# unzip, tar and gzip are needed by -RunTests to install the build and stage unit_tests.zip.
$packagesToInstall = @()

foreach ($pkg in @('ant', 'python3', 'dos2unix', 'rsync', 'unzip', 'tar', 'gzip')) {
    $rc = Invoke-Wsl -Command "which $pkg" -AllowFail -Quiet
    if ($rc -ne 0) { $packagesToInstall += $pkg } else { Write-Skip "$pkg already installed" }
}

foreach ($pkg in @('libxslt')) {
    $rc = Invoke-Wsl -Command "rpm -q $pkg" -AllowFail -Quiet
    if ($rc -ne 0) { $packagesToInstall += $pkg } else { Write-Skip "$pkg already installed" }
}

if ($packagesToInstall.Count -gt 0) {
    $pkgList = $packagesToInstall -join ' '
    Write-Warn "Installing: $pkgList"
    Invoke-Wsl -Command "dnf install -y $pkgList"
    Write-Ok "Installed: $pkgList"
}

# ---------------------------------------------------------------------------
# NativeFs: rsync the source tree into the WSL2 native filesystem
# ---------------------------------------------------------------------------
#
# Native ext4 layout inside the distro:
#   /root/sb/SyncWeave/   <- build root (no /mnt/ round-trips)
#
# Sync policy:
#   export/        excluded - re-created by the build; synced BACK afterwards
#   .git/objects/  excluded - pack files are large and not needed for builds
#   lib/ivy/       INCLUDED - reuses a prior resolve; avoids re-downloading JARs
# ---------------------------------------------------------------------------

$linuxRepoRoot = '/root/sb/SyncWeave'

if ($NativeFs) {
    if ($SkipSync) {
        # Verify the Linux copy exists before trusting it.
        $rc = Invoke-Wsl -Command "test -f '$linuxRepoRoot/build.xml'" -AllowFail -Quiet
        if ($rc -ne 0) {
            Stop-Build ("Linux repo not found at $linuxRepoRoot.`n" +
                "Run without -SkipSync first to populate it.")
        }
        Write-Step "NativeFs: skipping sync (-SkipSync)"
        Write-Skip "Using existing Linux copy at $linuxRepoRoot"
    } else {
        Write-Step "NativeFs: syncing repo into WSL2 native filesystem"
        Write-Host "    Source : $wslRepoRoot"
        Write-Host "    Dest   : $linuxRepoRoot"

        Invoke-Wsl -Command "mkdir -p '$linuxRepoRoot'"

        # Trailing slash on source = sync contents, not the directory name itself.
        # --delete removes files that no longer exist in the source.
        #
        # -rlt               : recurse, keep symlinks and modification times.
        #                      No -p/-o/-g: DrvFs (/mnt/c) owners and modes differ
        #                      from ext4 and would make every file look changed.
        # --modify-window=1    : treat mtimes within 1 s as equal (NTFS and ext4
        #                      store times at different precision).
        # Changed files are detected by size AND mtime, so an edit that keeps a
        # file's size is still copied.  (--size-only would miss it.)
        #
        # Excluded:
        #   .git/objects/    : git pack files, not needed for builds
        #   export/          : build output, re-created by ant
        Invoke-Wsl -Command ("rsync -rlt --modify-window=1 --delete --info=progress2 " +
            "--exclude='export/' " +
            "--exclude='.git/objects/' " +
            "'$wslRepoRoot/' '$linuxRepoRoot/'")

        Write-Ok "Sync complete"
    }

    # Repoint all build paths to the native Linux copy.
    $wslRepoRoot  = $linuxRepoRoot
    if ($jdkRelPath) { $wslJavaHome = "$wslRepoRoot/$jdkRelPath" }
    $wslToolsHome = "$wslRepoRoot/tools"
    $wslIvySrc    = "$wslRepoRoot/ivy/ivy.jar"

    Write-Ok "Build root      : $wslRepoRoot (native ext4)"
}

# ---------------------------------------------------------------------------
# Shell scripts must have LF line endings
# ---------------------------------------------------------------------------
# A Windows checkout (core.autocrlf=true) turns every shell script into CRLF.
# They then fail in bash with "$'\r': command not found", both during the
# build (build/scripts) and inside the product image built from this tree
# (install/bin/install.sh -> install_server.sh, bin/*.sh, launchers).

# ---------------------------------------------------------------------------
# Resolve ANT_HOME dynamically
# ---------------------------------------------------------------------------

Write-Step "Resolving ANT_HOME inside $DistroName"
# On RHEL/AlmaLinux the EPEL ant package installs to /usr/share/ant but the
# wrapper binary lives at /usr/bin/ant.  With --noconfig, ant computes
# ANT_HOME from dirname(realpath(/usr/bin/ant))/.. = /usr, which is wrong.
# Pre-setting ANT_HOME=/usr/share/ant (and export it) lets --noconfig find
# the correct lib directory.  We derive it by asking ant with ANT_HOME already
# set; this also works for standalone tarballs where ant is its own home.
$wslAntHome = Get-WslOutput "export ANT_HOME=/usr/share/ant; ant --noconfig -diagnostics 2>/dev/null | grep '^ant.home ' | head -1 | sed 's/.*: //'"
if (-not $wslAntHome) {
    # Fallback for standalone Ant tarballs: ant binary is in ANT_HOME/bin/
    $wslAntHome = Get-WslOutput 'dirname $(dirname $(readlink -f $(which ant)))'
}
if (-not $wslAntHome) {
    Stop-Build "Could not resolve ANT_HOME inside $DistroName. Is 'ant' installed?"
}
Write-Ok "ANT_HOME = $wslAntHome"
# Verify ant launches correctly with the bundled JDK (catches broken JAVA_HOME)
Invoke-Wsl -Command "export JAVA_HOME='$wslJavaHome'; export ANT_HOME='$wslAntHome'; export PATH='$wslJavaHome/bin:$WslCleanPath'; ant --noconfig -version"

# ---------------------------------------------------------------------------
# Ivy one-time setup
# ---------------------------------------------------------------------------

if (-not $SkipIvySetup) {
    Write-Step "Ivy setup: copying ivy.jar into ANT_HOME/lib"
    $ivyDest = "$wslAntHome/lib/ivy.jar"
    $rc = Invoke-Wsl -Command "test -f '$ivyDest'" -AllowFail -Quiet
    if ($rc -eq 0) {
        Write-Skip "ivy.jar already present in $wslAntHome/lib/"
    } else {
        Invoke-Wsl -Command "cp '$wslIvySrc' '$ivyDest'"
        Write-Ok "ivy.jar copied to $wslAntHome/lib/"
    }
} else {
    Write-Skip "Ivy setup skipped (-SkipIvySetup)"
}

# ---------------------------------------------------------------------------
# Auto-detect whether resolve is needed
# ---------------------------------------------------------------------------

$ivyLibPopulated = $false
if (-not $SkipResolve) {
    Write-Step "Checking lib/ivy/ for existing JARs"
    $jarCount = (Get-WslOutput "find '$wslRepoRoot/lib/ivy' -maxdepth 1 -name '*.jar' 2>/dev/null | wc -l").Trim()
    if ($jarCount -match '^\d+$' -and [int]$jarCount -gt 0) {
        $ivyLibPopulated = $true
        Write-Skip "lib/ivy/ contains $jarCount JARs - skipping resolve automatically"
    } else {
        Write-Ok "lib/ivy/ is empty - resolve will run"
    }
}

# ---------------------------------------------------------------------------
# Build the shell script
# ---------------------------------------------------------------------------
# Constructed as a string array and joined with newlines to avoid the
# empty-variable-in-here-string problem: when $preCleanBlock or $resolveBlock
# are empty strings and interpolated into @"..."@, bash receives a script
# with unexpected blank tokens that cause "syntax error: unexpected end of file".

$scriptLines = [System.Collections.Generic.List[string]]::new()
$scriptLines.Add('#!/bin/bash')
$scriptLines.Add('set -e')
$scriptLines.Add('')
$scriptLines.Add('# Start from a clean PATH - no Windows entries leaked via WSL interop.')
$scriptLines.Add("export PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'")
$scriptLines.Add("export JAVA_HOME='$wslJavaHome'")
$scriptLines.Add("export ANT_HOME='$wslAntHome'")
$scriptLines.Add("export TOOLS_HOME='$wslToolsHome'")
$scriptLines.Add('export PATH="$JAVA_HOME/bin:$ANT_HOME/bin:$PATH"')
$scriptLines.Add('')
$scriptLines.Add("cd '$wslRepoRoot'")

# Normalise every *.sh, plus extensionless files starting with "#!" in the
# repo root, bin/ and tools/, in the tree that is about to be built (by
# default the ext4 copy, so the Windows checkout is not touched).  Git with
# core.autocrlf=true checks these out with CRLF, which breaks install_server.sh.
# This runs inside the generated script, not through Invoke-Wsl, because
# wsl.exe hands -c commands to the distro's default shell first and that
# shell expands $variables before bash sees them.
# bin/servicemgr.sh and bin/tdiISCHome.sh are committed with CRLF and a
# Linux build ships them that way, so they are left alone to keep the
# output identical to a Linux build.
$scriptLines.Add('')
$scriptLines.Add('echo "==> Ensuring shell scripts have LF line endings"')
$scriptLines.Add('find . \( -path ./.git -o -path ./export -o -path ./ship \) -prune -o -type f -name "*.sh" ! -path ./bin/servicemgr.sh ! -path ./bin/tdiISCHome.sh -print0 | xargs -0 -r dos2unix -q')
$scriptLines.Add('for f in ./* ./bin/* ./tools/*; do')
$scriptLines.Add('    case "${f##*/}" in *.*) continue ;; esac')
$scriptLines.Add('    if [ -f "$f" ] && head -c2 "$f" | grep -q "^#!"; then dos2unix -q "$f"; fi')
$scriptLines.Add('done')
$scriptLines.Add('echo "    done"')

# -Clean: same effect as "ant clean" (deletes export/), done in bash because
# Ant's <delete> cannot remove the deep dojox tree over /mnt/c (MAX_PATH).
if ($Clean) {
    $scriptLines.Add('')
    $scriptLines.Add('echo "==> Clean: deleting export/ (same as ant clean)"')
    $scriptLines.Add("rm -rf '$wslRepoRoot/export'")
}

# MAX_PATH pre-clean: only needed on /mnt/c DrvFs. Native ext4 is fine.
if (-not $NativeFs -and -not $Clean) {
    $scriptLines.Add('')
    $scriptLines.Add("# Pre-delete deep dojox tree that defeats Ant's <delete> via /mnt/ MAX_PATH.")
    $scriptLines.Add('# bash rm -rf handles arbitrarily deep paths; Ant Java File.delete() cannot.')
    $scriptLines.Add('echo "==> Pre-cleaning deep OSGi output tree (MAX_PATH workaround)"')
    $scriptLines.Add("rm -rf '$wslRepoRoot/export/osgi'")
    $scriptLines.Add('echo "    done"')
}

$scriptLines.Add('')
$scriptLines.Add('echo ""')
$scriptLines.Add('echo "Java version:"')
$scriptLines.Add('java -version')
$scriptLines.Add('')
$scriptLines.Add('echo ""')
$scriptLines.Add('echo "Ant version:"')
$scriptLines.Add('ant --noconfig -version')

# Resolve: only when lib/ivy/ is empty.
if (-not $SkipResolve -and -not $ivyLibPopulated) {
    $scriptLines.Add('')
    $scriptLines.Add('echo ""')
    $scriptLines.Add('echo "==> ant resolve rename_jars"')
    $scriptLines.Add('ant --noconfig resolve rename_jars')
}

$scriptLines.Add('')
$scriptLines.Add('echo ""')
$scriptLines.Add("echo '==> ant $Target'")
$scriptLines.Add('# Capture ant real exit code. tee always exits 0; use PIPESTATUS.')
$scriptLines.Add('set +e')
$scriptLines.Add("ant --noconfig $Target 2>&1 | tee build.log")
$scriptLines.Add('ANT_RC=${PIPESTATUS[0]}')
$scriptLines.Add('set -e')
$scriptLines.Add('if [ $ANT_RC -ne 0 ]; then')
$scriptLines.Add('    echo "ant exited with code $ANT_RC" >&2')
$scriptLines.Add('    exit $ANT_RC')
$scriptLines.Add('fi')

# -- Unit-test phase -----------------------------------------------------------
# -RunTests implies -BuildTests: always compile + zip when either switch is set.
if ($BuildTests -or $RunTests) {
    $scriptLines.Add('')
    $scriptLines.Add('# -- Build unit-test JARs + deployable zip ------------------------------------')
    $scriptLines.Add('echo ""')
    $scriptLines.Add("echo '==> ant package_unit_tests zip_unit_tests'")
    $scriptLines.Add('set +e')
    $scriptLines.Add("ant --noconfig package_unit_tests zip_unit_tests 2>&1 | tee unit_tests_build.log")
    $scriptLines.Add('UT_BUILD_RC=${PIPESTATUS[0]}')
    $scriptLines.Add('set -e')
    $scriptLines.Add('if [ $UT_BUILD_RC -ne 0 ]; then')
    $scriptLines.Add('    echo "ant package_unit_tests zip_unit_tests exited with code $UT_BUILD_RC" >&2')
    $scriptLines.Add('    exit $UT_BUILD_RC')
    $scriptLines.Add('fi')
}

if ($RunTests) {
    $scriptLines.Add('')
    $scriptLines.Add('# -- Stage unit_tests.zip into install root + run suite -----------------------')
    $scriptLines.Add('echo ""')
    $scriptLines.Add("echo '==> Staging unit_tests.zip into install root'")
    $scriptLines.Add("REPO='$wslRepoRoot'")
    if ($AutoInstall) {
        $scriptLines.Add("INSTALL_ROOT='$TestInstallDir'")
        $scriptLines.Add('TEST_PKG="${INSTALL_ROOT}_pkg"')
        $scriptLines.Add('echo "==> Installing this build into ${INSTALL_ROOT}"')
        $scriptLines.Add('PKG=$(ls "${REPO}"/ship/install/SyncWeave-*-linux-x86_64.tgz 2>/dev/null | head -1)')
        $scriptLines.Add('if [ -z "${PKG}" ]; then echo "ERROR: no ship/install/SyncWeave-*-linux-x86_64.tgz; the images build did not produce the Linux installer." >&2; exit 1; fi')
        $scriptLines.Add('rm -rf "${INSTALL_ROOT}" "${TEST_PKG}"')
        $scriptLines.Add('mkdir -p "${TEST_PKG}"')
        $scriptLines.Add('tar -xzf "${PKG}" -C "${TEST_PKG}"')
        $scriptLines.Add('# install_server.sh creates INSTALL_ROOT (it must not exist yet).')
        $scriptLines.Add('# Run it with sh -x and keep its output in test_install.log for diagnosis.')
        $scriptLines.Add('set +e')
        $scriptLines.Add('(cd "${TEST_PKG}/linux-x86_64" && sh -x ./install_server.sh "${INSTALL_ROOT}") 2>&1 | tee "${REPO}/test_install.log"')
        $scriptLines.Add('INST_RC=${PIPESTATUS[0]}')
        $scriptLines.Add('set -e')
        $scriptLines.Add('if [ $INST_RC -ne 0 ] || [ ! -f "${INSTALL_ROOT}/bin/setupCmdLine.sh" ]; then')
        $scriptLines.Add('    echo "ERROR: installing the build failed (exit $INST_RC). See test_install.log." >&2')
        $scriptLines.Add('    exit 1')
        $scriptLines.Add('fi')
        $scriptLines.Add('rm -rf "${TEST_PKG}"')
    } else {
        $scriptLines.Add("INSTALL_ROOT='$TestInstallRoot'")
    }
    $scriptLines.Add('IJARS="${INSTALL_ROOT}/jars"')
    $scriptLines.Add('# Verify the install root has bin/setupCmdLine.sh before proceeding.')
    $scriptLines.Add('if [ ! -f "${INSTALL_ROOT}/bin/setupCmdLine.sh" ]; then')
    $scriptLines.Add('    echo "ERROR: ${INSTALL_ROOT}/bin/setupCmdLine.sh not found." >&2')
    $scriptLines.Add('    echo "       -TestInstallRoot must point to a product install, not just the build output." >&2')
    $scriptLines.Add('    exit 1')
    $scriptLines.Add('fi')
    $scriptLines.Add('mkdir -p "${IJARS}"')
    $scriptLines.Add('# Unzip unit_tests.zip - it already has CRLF-fixed scripts and all test JARs.')
    $scriptLines.Add('rm -rf "${INSTALL_ROOT}/unit_tests"')
    $scriptLines.Add('unzip -q "${REPO}/ship/tests/unit_tests.zip" -d "${INSTALL_ROOT}"')
    $scriptLines.Add('chmod +x "${INSTALL_ROOT}/unit_tests/bin/"*.sh')
    $scriptLines.Add('# Stage runtime JARs needed by the test bootloader classpath scanner.')
    $scriptLines.Add('cp "${REPO}/export/osgi/embedded/com.ibm.di.tp.server.jar"                   "${IJARS}/"')
    $scriptLines.Add('# Matched by pattern so a dependency version bump does not break staging;')
    $scriptLines.Add('# warn (instead of failing silently) when nothing matches.')
    $scriptLines.Add('stage_jar() {')
    $scriptLines.Add('    local found=0 f')
    $scriptLines.Add('    for f in "$1"/$2; do')
    $scriptLines.Add('        if [ -f "$f" ]; then cp "$f" "${IJARS}/"; found=1; fi')
    $scriptLines.Add('    done')
    $scriptLines.Add('    if [ $found -eq 0 ]; then echo "WARNING: no JAR matching $1/$2" >&2; fi')
    $scriptLines.Add('}')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/unittest"     "junit-dep*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/unittest"     "hamcrest-all*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/unittest"     "hamcrest-core*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/unittest"     "easymock*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/unittest"     "spring-test*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/export/osgi/runtime" "org.eclipse.equinox.http.service.api*.jar"')
    $scriptLines.Add('stage_jar "${REPO}/lib/ivy/axis-2"       "jaxb-runtime-*.jar"')
    $scriptLines.Add('')
    $scriptLines.Add('echo ""')
    $scriptLines.Add("echo '==> Running unit test suite'")
    $scriptLines.Add('# env -i leaves no locale, so Java would treat non-ASCII text as "?".')
    $scriptLines.Add('# C.UTF-8 ships with AlmaLinux 9 glibc, no langpack needed.')
    $scriptLines.Add('export LANG=C.UTF-8 LC_ALL=C.UTF-8')
    $scriptLines.Add('set +e')
    $scriptLines.Add('"${INSTALL_ROOT}/unit_tests/bin/runTestSuite.sh" 2>&1 | tee /tmp/syncweave_test_run.log')
    $scriptLines.Add('TEST_RC=${PIPESTATUS[0]}')
    $scriptLines.Add('set -e')
    $scriptLines.Add("cp /tmp/syncweave_test_run.log '$wslRepoRoot/test_run.log' 2>/dev/null || true")
    $scriptLines.Add('FAILURE_COUNT=$(grep -c "<TestHeader>" /tmp/syncweave_test_run.log 2>/dev/null)')
    $scriptLines.Add('FAILURE_COUNT=${FAILURE_COUNT:-0}')
    $scriptLines.Add('echo ""')
    $scriptLines.Add('echo "Test failures: ${FAILURE_COUNT}"')
    $scriptLines.Add('if [ "${FAILURE_COUNT}" -ne 0 ] || [ "${TEST_RC}" -ne 0 ]; then')
    $scriptLines.Add('    echo "UNIT TEST FAILED (failures=${FAILURE_COUNT}, exit=${TEST_RC})" >&2')
    $scriptLines.Add('    exit 1')
    $scriptLines.Add('fi')
    $scriptLines.Add('echo "UNIT TEST PASSED"')
}

# Join with Unix LF only. Writing via PowerShell pipe sends UTF-16/CRLF which
# corrupts the bash script. Instead write to a temp Windows file as UTF-8 LF,
# then copy it into WSL where it is already on the native filesystem.
$buildScript = $scriptLines -join "`n"

Write-Step "Writing build script to /tmp/syncweave_build.sh"

# Write UTF-8 without BOM, LF line endings to a temp file on Windows.
$tmpScript = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmpScript, $buildScript, (New-Object System.Text.UTF8Encoding $false))

# Copy into WSL via the /mnt/ path (no pipe = no encoding corruption).
$tmpScriptWsl = ConvertTo-WslPath $tmpScript
Invoke-Wsl -Command "cp '$tmpScriptWsl' /tmp/syncweave_build.sh && chmod +x /tmp/syncweave_build.sh"
Remove-Item $tmpScript -ErrorAction SilentlyContinue

Write-Ok "Build script written"

# ---------------------------------------------------------------------------
# Execute
# ---------------------------------------------------------------------------

$stepLabel = "ant $Target"
if ($BuildTests -or $RunTests) { $stepLabel += ' + package_unit_tests' }
if ($RunTests)                  { $stepLabel += ' + runTestSuite' }
Write-Step "Running: $stepLabel"
Write-Host ""

$buildStart = Get-Date
& wsl -d $DistroName -u root -- env '-i' "PATH=$WslCleanPath" 'HOME=/root' bash --noprofile --norc /tmp/syncweave_build.sh
$buildExitCode = $LASTEXITCODE
$buildElapsed = [math]::Round(((Get-Date) - $buildStart).TotalSeconds)

# ---------------------------------------------------------------------------
# NativeFs: sync outputs back to the Windows repo
# ---------------------------------------------------------------------------

if ($NativeFs) {
    # $repoRoot is the original Windows clone root (never repointed).
    $winRepoWsl = ConvertTo-WslPath $repoRoot

    # Always copy build logs back so the user can inspect them regardless of outcome.
    Invoke-Wsl -Command "cp '$linuxRepoRoot/build.log' '$winRepoWsl/build.log' 2>/dev/null || true" -AllowFail
    if ($BuildTests -or $RunTests) {
        Invoke-Wsl -Command "cp '$linuxRepoRoot/unit_tests_build.log' '$winRepoWsl/unit_tests_build.log' 2>/dev/null || true" -AllowFail
    }
    if ($RunTests) {
        Invoke-Wsl -Command "cp '$linuxRepoRoot/test_run.log' '$winRepoWsl/test_run.log' 2>/dev/null || true" -AllowFail
        Invoke-Wsl -Command "cp '$linuxRepoRoot/test_install.log' '$winRepoWsl/test_install.log' 2>/dev/null || true" -AllowFail
    }

    if ($SkipSyncBack) {
        Write-Step "NativeFs: skipping sync-back (-SkipSyncBack)"
        if ($buildExitCode -eq 0) {
            Write-Host ""
            Write-Host "  Build outputs are on the WSL2 native filesystem:" -ForegroundColor Cyan
            Write-Host ("    {0,-25} {1}" -f 'Server JARs',          "\\wsl`$\$DistroName\root\sb\SyncWeave\export\jars\")
            Write-Host ("    {0,-25} {1}" -f 'OSGi embedded JARs',   "\\wsl`$\$DistroName\root\sb\SyncWeave\export\osgi\embedded\")
            Write-Host ("    {0,-25} {1}" -f 'CE RCP platform zips', "\\wsl`$\$DistroName\root\sb\SyncWeave\export\ce_eclipse\")
            if ($Target -eq 'images') {
                Write-Host ("    {0,-25} {1}" -f 'Distributable zips',  "\\wsl`$\$DistroName\root\sb\SyncWeave\export\zip_bundles\")
                Write-Host ("    {0,-25} {1}" -f 'Javadoc',             "\\wsl`$\$DistroName\root\sb\SyncWeave\export\docs\api\")
                Write-Host ("    {0,-25} {1}" -f 'Installer staging',   "\\wsl`$\$DistroName\root\sb\SyncWeave\ship\install\")
            }
        }
    } else {
        Write-Step "NativeFs: syncing outputs back to Windows repo"

        if ($buildExitCode -eq 0) {
            # Same change detection as the sync-in (size and mtime, 1 s window).
            Write-Host "    Syncing export/ ..."
            Invoke-Wsl -Command "rsync -rlt --modify-window=1 --delete --info=progress2 '$linuxRepoRoot/export/' '$winRepoWsl/export/'"
            if ($Target -eq 'images') {
                Write-Host "    Syncing ship/ ..."
                Invoke-Wsl -Command "rsync -rlt --modify-window=1 --delete --info=progress2 '$linuxRepoRoot/ship/' '$winRepoWsl/ship/'"
            }
            Write-Ok "Outputs synced to $repoRoot"
            Write-Host ""
            Write-Host "  Build outputs are in:" -ForegroundColor Cyan
            Write-Host ("    {0,-25} {1}" -f 'Server JARs',          (Join-Path $repoRoot 'export\jars\'))
            Write-Host ("    {0,-25} {1}" -f 'OSGi embedded JARs',   (Join-Path $repoRoot 'export\osgi\embedded\'))
            Write-Host ("    {0,-25} {1}" -f 'CE RCP platform zips', (Join-Path $repoRoot 'export\ce_eclipse\'))
            if ($Target -eq 'images') {
                Write-Host ("    {0,-25} {1}" -f 'Distributable zips',  (Join-Path $repoRoot 'export\zip_bundles\'))
                Write-Host ("    {0,-25} {1}" -f 'Javadoc',             (Join-Path $repoRoot 'export\docs\api\'))
                Write-Host ("    {0,-25} {1}" -f 'Installer staging',   (Join-Path $repoRoot 'ship\install\'))
            }
        } else {
            Write-Warn "Build failed - skipping export/ sync (partial outputs not copied back)"
        }
    }
}

# ---------------------------------------------------------------------------
# Result
# ---------------------------------------------------------------------------

Write-Host ""
Write-Step "Build result"

$buildLogWin   = Join-Path $repoRoot 'build.log'
$utBuildLogWin = Join-Path $repoRoot 'unit_tests_build.log'
$testRunLogWin = Join-Path $repoRoot 'test_run.log'
$resultLine    = ''
if (Test-Path $buildLogWin) {
    $resultLine = (Select-String -Path $buildLogWin -Pattern 'BUILD SUCCESSFUL|BUILD FAILED' |
        Select-Object -Last 1).Line
}

# Require BOTH: log says BUILD SUCCESSFUL AND the shell exited 0.
# The previous -or caused false positives: an earlier phase wrote
# "BUILD SUCCESSFUL" to the log even when a later phase (OSGi clean) failed.
if ($resultLine -match 'BUILD SUCCESSFUL' -and $buildExitCode -eq 0) {
    Write-Host "`n  BUILD SUCCESSFUL  ($buildElapsed seconds)" -ForegroundColor Green

    # Only print output paths here when NOT using NativeFs - with NativeFs they
    # were already printed immediately after the sync-back completed above.
    if (-not $NativeFs) {
        Write-Host "`n  Key output paths:" -ForegroundColor Cyan
        $outputs = [ordered]@{
            'Server JARs'          = 'export\jars\'
            'OSGi embedded JARs'   = 'export\osgi\embedded\'
            'CE RCP platform zips' = 'export\ce_eclipse\'
        }
        if ($Target -eq 'images') {
            $outputs['Distributable zips'] = 'export\zip_bundles\'
            $outputs['Javadoc']            = 'export\docs\api\'
            $outputs['Installer staging']  = 'ship\install\'
        }
        foreach ($label in $outputs.Keys) {
            Write-Host ("    {0,-25} {1}" -f $label, (Join-Path $repoRoot $outputs[$label]))
        }
    }

    # Unit-test summary
    if ($BuildTests -or $RunTests) {
        Write-Host ""
        Write-Host "  Unit test JARs  : $(Join-Path $repoRoot 'export\unit_tests\')" -ForegroundColor Cyan
        Write-Host "  Unit test zip   : $(Join-Path $repoRoot 'ship\tests\unit_tests.zip')" -ForegroundColor Cyan
        Write-Host "  Unit test build log : $utBuildLogWin"
    }
    if ($RunTests) {
        Write-Host ""
        if (Test-Path $testRunLogWin) {
            $failCount = (Select-String -Path $testRunLogWin -Pattern '<TestHeader>' -AllMatches |
                Measure-Object).Count
            if ($failCount -eq 0) {
                Write-Host "  UNIT TESTS PASSED" -ForegroundColor Green
            } else {
                Write-Host "  UNIT TESTS FAILED  ($failCount failure(s))" -ForegroundColor Red
                Write-Host "  Test log : $testRunLogWin" -ForegroundColor Yellow
            }
        } else {
            Write-Warn "Unit-test run log not found at $testRunLogWin"
        }
        Write-Host "  Unit test run log   : $testRunLogWin"
    }

    Write-Host ""
    exit 0
} else {
    Write-Host "`n  BUILD FAILED" -ForegroundColor Red
    Write-Host "  Full log: $buildLogWin" -ForegroundColor Yellow
    Write-Host "  Tip: check export\ce_rcp_error.log for PDE build errors." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}
