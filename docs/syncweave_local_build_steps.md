# SyncWeave — Host Build with Java 21

**Host:** `xxx.myhost.com` (RHEL 9, x86_64)

**Java:** IBM Semeru OpenJDK 21.0.12 (`<PROJECT_HOME>/adks/ibm/jdk/jdk-21.0.12+8`)

**Ant:** 1.10.17 (`<PROJECT_HOME>/ant/apache-ant-1.10.17`)

**Eclipse PDE:** 4.39.0 (`<PROJECT_HOME>/tools/eclipse/eclipse_runtime/4.39.0`)

**Workspace:** `<PROJECT_HOME>/SyncWeave`

---

## Prerequisites

### 1. Clone the SyncWeave repository

```bash
git clone git@github.com:ibm-verify/vdi-syncweave.git <PROJECT_HOME>/SyncWeave
cd <PROJECT_HOME>/SyncWeave
```

### 2. Download JDK/JREs and verify Java 21 is available

```bash
<PROJECT_HOME>/SyncWeave/setup-jdk.sh
<PROJECT_HOME>/SyncWeave/adks/ibm/jdk/jdk-21.0.12+8/bin/java -version
```

Expected output:
```
openjdk version "21.0.12" 2026-07-21 LTS
IBM Semeru Runtime Open Edition 21.0.12.0 (build 21.0.12+8-LTS)
```

### 3. Place Ivy in Ant's lib directory (one-time setup)

Ivy must be loaded by Ant's system classloader at startup, **not** via a
`<taskdef>` inside the build. Loading it via `<taskdef>` creates a child
classloader. If the `resolve` target runs more than once in the same
build (e.g. as a dependency of both `resolve` and `rename_jars`), the two
classloader instances produce incompatible class objects, causing:
```
ClassCastException: DefaultModuleDescriptor incompatible with ModuleDescriptor
```

Copy `ivy.jar` into `ANT_HOME/lib` once:

```bash
cp <PROJECT_HOME>/SyncWeave/ivy/ivy.jar \
   <PROJECT_HOME>/ant/apache-ant-1.10.17/lib/ivy.jar
```

Verify:
```bash
ls <PROJECT_HOME>/ant/apache-ant-1.10.17/lib/ivy.jar
```

### 4. Verify the tools tree is present

```bash
ls <PROJECT_HOME>/SyncWeave/tools/eclipse/eclipse_runtime/4.39.0/eclipse/plugins/org.eclipse.pde.build_*
```

Expected output:
```
org.eclipse.pde.build_3.12.900.v20260121-0829
```


## Environment Variables

Set these in your shell before running any `ant` command.
You can put them in `~/.bashrc` or a local `env.sh` script.

```bash
# Java 21 from adks
export JAVA_HOME=<PROJECT_HOME>/SyncWeave/adks/ibm/jdk/jdk-21.0.12+8
export PATH=$JAVA_HOME/bin:$PATH

# Tools tree (equivalent of /build/tools_git in Docker)
export TOOLS_HOME=<PROJECT_HOME>/SyncWeave/tools

# Ant 1.10.17 standalone installation
export ANT_HOME=<PROJECT_HOME>/ant/apache-ant-1.10.17
export PATH=$ANT_HOME/bin:$PATH
```

Verify:
```bash
java -version          # must show 21.x
ant -version           # must show Apache Ant 1.10.17
echo $TOOLS_HOME       # <PROJECT_HOME>/tools
```

---

## Build Steps

### Step 1 — Set environment

```bash
export JAVA_HOME=<PROJECT_HOME>/SyncWeave/adks/ibm/jdk/jdk-21.0.12+8
export PATH=$JAVA_HOME/bin:$PATH
export TOOLS_HOME=<PROJECT_HOME>/SyncWeave/tools
export ANT_HOME=<PROJECT_HOME>/ant/apache-ant-1.10.17
export PATH=$ANT_HOME/bin:$PATH
```

### Step 2 — Patch org.eclipse.osgi (first time only, idempotent) (Optional)

> **Note:** Eclipse 4.39.0's `org.eclipse.osgi_3.24.100` already ships
> `JavaSE-21.profile` and earlier profiles natively. On a clean 4.39.0
> tools tree this patch has no effect. The preSetup target in ce_rcp/customTargets.xml 
> runs it automatically as a precaution so, no manual action is required.

The patch injects JavaSE-10 through JavaSE-25 profile descriptors into the
Eclipse OSGi JAR so that the PDE resolver accepts modern BREE declarations.
You can run it manually to verify:

```bash
chmod +x build/scripts/patch_osgi_ee_profiles.sh
build/scripts/patch_osgi_ee_profiles.sh \
  $TOOLS_HOME/eclipse/eclipse_runtime/4.39.0/eclipse/plugins
```

Expected output (4.39.0 already patched):
```
org.eclipse.osgi already patched with JavaSE-11+ profiles, skipping.
```

### Step 3 — Resolve Ivy dependencies

```bash
cd <PROJECT_HOME>/SyncWeave
ant resolve rename_jars
```

This downloads all third-party JARs into `lib/ivy/`. On a machine with no
internet access, copy `lib/ivy/` from a previous successful build.

### Step 4 — Build

Two targets are available depending on what you need:

#### `ant package` — developer build (faster)

Compiles all Java, builds OSGi bundles, and produces the CE RCP zips.
Skips javadoc, installer zip assembly, and the `ship/` tree.

```bash
cd <PROJECT_HOME>/SyncWeave
ant package 2>&1 | tee build.log
```

**Outputs after `ant package`:**

| Path | Contents |
|---|---|
| `export/jars/` | All server JARs (`miserver.jar`, `miconfig.jar`, `diserverapi.jar`, connectors, functions, parsers, …) |
| `export/osgi/embedded/` | OSGi plugin JARs (`com.ibm.di.*.jar`) — used by the CE RCP build |
| `export/osgi/runtime/` | Normalised (version-stripped) OSGi runtime bundle layout |
| `export/ce_eclipse/` | CE RCP platform zips — `eclipsece-linux.gtk.x86_64.zip`, `eclipsece-win32.win32.x86_64.zip`, `eclipsece-macosx.cocoa.x86_64.zip`, `eclipsece-macosx.cocoa.aarch64.zip`, and `TDI_CEUpdateSite.zip` |
| `export/ce_rcp/` | Intermediate CE RCP build workspace (PDE builder staging area) |

**How to use the CE RCP zip (Linux example):**

```bash
cd /tmp
unzip <PROJECT_HOME>/SyncWeave/export/ce_eclipse/eclipsece-linux.gtk.x86_64.zip
./eclipsece/miadmin
```

#### `ant images` — full release build

Runs `ant package` plus javadoc generation, installer zip assembly, and
populates the `ship/` tree with all distributable zip bundles.

```bash
cd <PROJECT_HOME>/SyncWeave
ant images 2>&1 | tee build.log
```

**Additional outputs after `ant images` (on top of `ant package`):**

| Path | Contents |
|---|---|
| `export/zip_bundles/` | All distributable zips: `TDI_Base.zip`, `TDI_BaseWin.zip`, `TDI_BaseUNIX.zip`, `TDI_Server.zip`, `TDI_CEWin.zip`, `TDI_CEUNIX.zip`, `TDI_Plugins_Base.zip`, `TDI_Plugins_BaseWin.zip`, `TDI_Docs.zip`, `TDI_Examples.zip`, … |
| `export/docs/api/` | Public Javadoc |
| `export/docs_internal/api/` | Internal Javadoc |
| `ship/install/` | Installer staging tree (input to `ant build_installer`) |
| `ship/zip_bundles/` | Copy of all distributable zips for shipping |

**How to use the installer zips (`ant images` output):**

On Linux/macOS — run `install.sh` from the `ship/install/` tree pointing at
the zips in `ship/zip_bundles/` (or `export/zip_bundles/`):

```bash
ship/install/install_ce.sh /tmp/my_syncweave_install
```

On Windows — run `install_ce.vbs` (or `install.vbs` for server-only):

```
cscript ship\install\install_ce.vbs E:\my_syncweave_install
```

### Step 5 — Check for success

```bash
grep "BUILD SUCCESSFUL\|BUILD FAILED" build.log
```

Output should be:
```
BUILD SUCCESSFUL
```

---

## Building on Windows (WSL2)

The build is not native-Windows: `build.xml` shells out to `sed` and `/bin/sh`.
On Windows, `build-SyncWeave.ps1` in the repository root runs the same Ant build
inside an AlmaLinux 9 WSL2 distro (a 1:1 RHEL 9 rebuild), reading the repo
through `/mnt/c`. It installs Ant (from EPEL), python3, libxslt, dos2unix, rsync
and unzip in the distro, places `ivy.jar` in `ANT_HOME/lib`, runs
`ant resolve rename_jars` when `lib/ivy/` is empty, then runs the target and
writes `build.log` in the repo root.

### One-time setup

1. Enable WSL2 (`wsl --install`, Windows 10 build 19041+ or Windows 11).
2. Import AlmaLinux 9. It is not in the Microsoft Store, so import the image
   from the [AlmaLinux WSL releases](https://github.com/AlmaLinux/wsl-images/releases)
   (the script's help shows the exact, tested release URL):

   ```powershell
   Invoke-WebRequest -Uri "<AlmaLinux 9 .wsl image URL>" -OutFile "$env:TEMP\AlmaLinux9.wsl" -UseBasicParsing
   New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\WSL\AlmaLinux9"
   wsl --import AlmaLinux9 "$env:LOCALAPPDATA\WSL\AlmaLinux9" "$env:TEMP\AlmaLinux9.wsl"
   wsl -d AlmaLinux9 -- cat /etc/redhat-release
   ```

3. Download the bundled JDK into the clone (`setup-jdk.sh`, run inside the distro):

   ```powershell
   cd C:\path\to\vdi-syncweave
   wsl -d AlmaLinux9 -- bash -c 'dnf install -y wget tar gzip && bash <(tr -d \\r < setup-jdk.sh)'
   ```

### Build

From the repository root in PowerShell:

```powershell
.\build-SyncWeave.ps1                    # ant package
.\build-SyncWeave.ps1 -Target images     # full release build
.\build-SyncWeave.ps1 -Clean             # delete export\ first (same as ant clean)
.\build-SyncWeave.ps1 -MountedFs         # build over /mnt/c instead of ext4 (much slower, see below)
.\build-SyncWeave.ps1 -BuildTests        # also build unit-test JARs and ship\tests\unit_tests.zip
.\build-SyncWeave.ps1 -RunTests          # build images, install it in WSL, run the JUnit suite
```

If script execution is blocked, run it with
`powershell -ExecutionPolicy Bypass -File .\build-SyncWeave.ps1`.
Run `Get-Help .\build-SyncWeave.ps1 -Full` for all options (`-RepoPath`,
`-JavaHome`, `-DistroName`, `-SkipResolve`, `-SkipIvySetup`, `-SkipSync`, `-SkipSyncBack`).

By default the script rsyncs the clone to ext4 inside WSL (`/root/sb/SyncWeave`),
builds there, and syncs `export\` (and `ship\` for `images`) back to Windows.
A full `images` build takes about 8 minutes this way. Building directly over
`/mnt/c` with `-MountedFs` is far slower (a test run was stopped after an hour).

The JDK is not hardcoded: the script uses the newest `jdk-*` directory under
`adks\ibm\jdk\` (where `setup-jdk.sh` puts it), so a JDK version bump needs no
script change. Pass `-JavaHome <path>` to pick a different Linux JDK.

Outputs land in the usual `export\` and `ship\` folders of the Windows clone.

`-RunTests` needs a Linux product install, because `unit_tests/bin/runTestSuite.sh`
loads `../../bin/setupCmdLine.sh`. By default the script builds `images`, unpacks
`ship\install\SyncWeave-*-linux-x86_64.tgz` and installs it with
`install_server.sh` into `/root/sb/test_install` inside the distro (recreated
on every run), then stages `unit_tests` and the runtime JARs and runs the suite.
This needs the Linux JRE that `setup-jdk.sh` puts in `adks\ibm\jre\`. To test
against an existing Linux install instead, pass `-TestInstallRoot <path>`.
Results are in `unit_tests_build.log` and `test_run.log` in the repo root.

---

## Troubleshooting

### `TOOLS_HOME is not set` or `project.tools_home` resolves to empty

`project_setup.xml` reads `TOOLS_HOME` from the environment via
`${project_env_vars.TOOLS_HOME}`. Ensure it is exported before running ant:

```bash
echo $TOOLS_HOME   # must not be blank
export TOOLS_HOME=<PROJECT_HOME>/SyncWeave/tools
```

### `Unable to create javax script engine for javascript`

The `nashorn-core.jar` must be on the classpath for `osgi/build.xml` tasks.
It is picked up automatically from `lib/ivy/` after `ant resolve`.
If you skipped the Ivy resolve step, run it.

### PDE build fails: `Host plug-in JavaSE_0.0.0 has not been found`

This should not occur with Eclipse 4.39.0 as its `org.eclipse.osgi` already
includes modern EE profiles. If it does appear, re-run the OSGi patch script
(Step 2). This can happen if the tools tree was refreshed or the JAR was replaced:

```bash
build/scripts/patch_osgi_ee_profiles.sh \
  $TOOLS_HOME/eclipse/eclipse_runtime/4.39.0/eclipse/plugins
```

### `Overriding a previous definition of ivy:settings is not allowed`

Caused by Ant 1.10.9+ changing the default `override` mode from `yes` to
`notallowed`. Fixed in `build.xml` by adding `onerror="ignore"` to the
`<taskdef>` and `override="true"` to `<ivy:configure>`. If you see this
again it means an older unpatched `build.xml` is being used — verify the
file matches the version in this repository.

### `exec returned: 13` from `eclipse_rules.xml`

Check `export/ce_rcp_error.log` (the `errorlog.log` passed to the macro).
This is the PDE headless build stderr output and will contain the actual error.

### Javadoc errors: `package org.eclipse.*.* does not exist`

These are non-fatal warnings during `javadoc_internal`. They do not affect
`ant package`. The missing packages are Eclipse-internal APIs not needed
in the shipped Javadoc. Run `ant package` instead of `ant images` to skip
javadoc generation entirely.

### PDE product build: `Package uses conflict: aQute.bnd.*`

Eclipse 4.39.0's bnd jars (`biz.aQute.bndlib`, `biz.aQute.repository`,
`biz.aQute.resolve`) have intrinsic uses-constraint wiring conflicts on
`aQute.bnd.*` packages. This affects `org.eclipse.pde.core` resolution
during the CE product build. The `build_eclipse_plugin` macro in
`rules_mk/eclipse_rules.xml` passes `-Dresolution.devMode=true` to
suppress uses-constraint checking (only) for the product build. This
behaviour is expected and correct. It does **not** hide missing bundle errors.

---

## Quick Reference: One-liner for clean full build

```bash
export JAVA_HOME=<PROJECT_HOME>/SyncWeave/adks/ibm/jdk/jdk-21.0.12+8 && \
export TOOLS_HOME=<PROJECT_HOME>/SyncWeave/tools && \
export ANT_HOME=<PROJECT_HOME>/ant/apache-ant-1.10.17 && \
export PATH=$JAVA_HOME/bin:$ANT_HOME/bin:$PATH && \
cd <PROJECT_HOME>/SyncWeave && \
ant images 2>&1 | tee build.log && \
grep "BUILD SUCCESSFUL\|BUILD FAILED" build.log
```
