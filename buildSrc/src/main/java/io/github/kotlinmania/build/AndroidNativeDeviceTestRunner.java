package io.github.kotlinmania.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Executes native property tests and verifies availability before connected instrumentation tests. */
public final class AndroidNativeDeviceTestRunner {
    private static final String PHASE_PROPERTY = "kotlinmania.localPublish.phase";
    private static final Set<String> ABIS = Set.of("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "riscv64");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private AndroidNativeDeviceTestRunner() {}

    record CommandResult(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    interface CommandRunner {
        CommandResult execute(List<String> command, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    interface EmulatorFinder {
        List<String> find() throws Exception;
    }

    record Device(String serial, String primaryAbi, List<String> supportedAbis) {}

    record Configuration(Path project, String target, Path adb, String serial) {}

    static final class RuntimeUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        RuntimeUnavailableException(String message) {
            super(message);
        }
    }

    private static String abi(String target) {
        return switch (target) {
            case "androidNativeArm64" -> "arm64-v8a";
            case "androidNativeX64" -> "x86_64";
            default -> throw new IllegalArgumentException("Unsupported Android Native target: " + target);
        };
    }

    static Map<String, String> parseDevices(String output) {
        List<String> lines = output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        require(!lines.isEmpty() && lines.getFirst().equals("List of devices attached"), "Malformed adb device inventory");
        Map<String, String> devices = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\\s+");
            require(fields.length >= 2 && validSerial(fields[0]), "Malformed adb device row: " + line);
            String state = fields[1];
            if (state.equals("no") && fields.length >= 3 && fields[2].equals("permissions")) state = "no permissions";
            require(Set.of("device", "offline", "unauthorized", "bootloader", "recovery", "sideload", "host", "no permissions").contains(state),
                    "Unknown adb device state: " + line);
            require(devices.put(fields[0], state) == null, "Repeated adb device serial: " + fields[0]);
        }
        return devices;
    }

    private static boolean validSerial(String serial) {
        return serial != null && serial.matches("[A-Za-z0-9._:\\[\\]-]+");
    }

    private static Device inspectDevice(String adb, String serial, CommandRunner runner) throws Exception {
        String primary = successful(runner, List.of(adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abi"), PROBE_TIMEOUT).strip();
        String supported = successful(runner, List.of(adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abilist"), PROBE_TIMEOUT).strip();
        List<String> abis = List.of(supported.split(",", -1)).stream().map(String::strip).toList();
        require(ABIS.contains(primary) && !abis.isEmpty() && abis.stream().allMatch(ABIS::contains)
                        && abis.contains(primary) && new TreeSet<>(abis).size() == abis.size(),
                "Invalid Android ABI response from " + serial + ": " + primary + " / " + supported);
        return new Device(serial, primary, abis);
    }

    static Device selectDevice(String adb, String target, String requestedSerial, CommandRunner runner) throws Exception {
        String requiredAbi = abi(target);
        if (requestedSerial != null) require(validSerial(requestedSerial), "Invalid explicit Android device serial");
        Map<String, String> inventory = parseDevices(successful(runner, List.of(adb, "devices", "-l"), PROBE_TIMEOUT));
        if (requestedSerial != null) {
            require(inventory.containsKey(requestedSerial), "Explicit Android device is absent: " + requestedSerial);
            require(inventory.get(requestedSerial).equals("device"), "Explicit Android device is not online: " + requestedSerial);
            Device device = inspectDevice(adb, requestedSerial, runner);
            require(device.supportedAbis().contains(requiredAbi), "Explicit Android device " + requestedSerial + " does not support " + requiredAbi);
            return device;
        }
        List<Device> compatible = new ArrayList<>();
        for (String serial : inventory.keySet().stream().sorted().toList()) {
            if (!inventory.get(serial).equals("device")) continue;
            Device device = inspectDevice(adb, serial, runner);
            if (device.supportedAbis().contains(requiredAbi)) compatible.add(device);
        }
        if (!compatible.isEmpty()) return compatible.getFirst();
        require(inventory.values().stream().allMatch("device"::equals),
                "ADB reports offline, unauthorized or otherwise unusable devices; compatible runtime absence cannot be established: " + inventory);
        throw new RuntimeUnavailableException("No online Android device supports " + requiredAbi + " for " + target);
    }

    static List<String> verifyRuntimeLog(String log) {
        require(!log.contains("testIgnored") && !log.contains("testFailed"), "Android Native tests reported failed or ignored cases");
        List<String> expected = AndroidRuntimeResultsVerifier.NATIVE_CASES.stream().sorted().toList();
        for (String event : List.of("testStarted", "testFinished")) {
            List<String> actual = Pattern.compile("(?m)^##teamcity\\[" + event + " name='([^']+)'")
                    .matcher(log).results().map(match -> match.group(1)).sorted().toList();
            require(actual.equals(expected), "Android Native " + event + " cases differ from the required tests: " + actual);
        }
        return expected;
    }

    private static String successful(CommandRunner runner, List<String> command, Duration timeout) throws Exception {
        CommandResult result = runner.execute(command, timeout);
        if (result.exitCode() != 0) {
            throw new IOException("Android command failed (" + result.exitCode() + "): " + command + "\n" + result.stdout() + result.stderr());
        }
        return result.stdout();
    }

    /** Ordinary/remote invocations remain AGP's responsibility and never receive an availability exemption. */
    public static boolean shouldRunConnectedTests(Path project, Path adb, int minimumSdk,
                                                  String requestedSerial, String environmentSerial) throws Exception {
        Path root = project.toRealPath();
        Path report = root.resolve("build/reports/runtime/connectedAndroidDeviceTest");
        return connectedTestsAvailable(root, adb, minimumSdk, requestedSerial, environmentSerial,
                System.getProperty(PHASE_PROPERTY),
                recordingRunner(root, root.resolve("build/tmp/android-connected-device-tests"), report.resolve("device-probes.json")),
                () -> installedCompatibleEmulators(adb, minimumSdk));
    }

    static boolean connectedTestsAvailable(Path project, Path adb, int minimumSdk, String requestedSerial,
                                           String environmentSerial, String phase, CommandRunner runner,
                                           EmulatorFinder emulators) throws Exception {
        Path receiptFile = project.resolve("build/reports/runtime/connectedAndroidDeviceTest/availability.json");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("task", "connectedAndroidDeviceTest");
        receipt.put("local_publish_phase", phase == null ? "" : phase);
        receipt.put("minimum_sdk", minimumSdk);
        if (!("check".equals(phase) || "publish".equals(phase))) {
            receipt.put("status", "required");
            Json.write(receiptFile, receipt);
            return true;
        }
        receipt.put("status", "probing");
        try {
            require(minimumSdk > 0, "Invalid connected-test minimum Android API");
            require(requestedSerial == null || environmentSerial == null || requestedSerial.equals(environmentSerial),
                    "Conflicting explicit Android device selections: android.injected.device.serial and ANDROID_SERIAL");
            String selected = requestedSerial != null ? requestedSerial : environmentSerial;
            receipt.put("requested_serial", selected == null ? "" : selected);
            if (selected != null) require(validSerial(selected), "Invalid explicit Android device serial");
            Map<String, String> inventory = parseDevices(successful(runner, List.of(adb.toString(), "devices", "-l"), PROBE_TIMEOUT));
            receipt.put("inventory", inventory);
            if (selected != null) {
                require(inventory.containsKey(selected), "Explicit Android device is absent: " + selected);
                require(inventory.get(selected).equals("device"), "Explicit Android device is not online: " + selected);
            }
            List<String> online = selected == null
                    ? inventory.keySet().stream().filter(serial -> inventory.get(serial).equals("device")).sorted().toList()
                    : List.of(selected);
            // This project's instrumentation APK is JVM/reflection-only, without native ABI restrictions.
            Map<String, Integer> supported = new LinkedHashMap<>();
            for (String serial : online) {
                String api = successful(runner, List.of(adb.toString(), "-s", serial, "shell", "getprop", "ro.build.version.sdk"), PROBE_TIMEOUT).strip();
                require(api.matches("[0-9]+") && Integer.parseInt(api) > 0, "Invalid Android API response from " + serial + ": " + api);
                int level = Integer.parseInt(api);
                if (selected != null) require(level >= minimumSdk, "Explicit Android device does not meet API " + minimumSdk + ": " + selected);
                if (level < minimumSdk) continue;
                String boot = successful(runner, List.of(adb.toString(), "-s", serial, "shell", "getprop", "sys.boot_completed"), PROBE_TIMEOUT).strip();
                require(boot.equals("1"), "Compatible Android device has not completed boot: " + serial);
                supported.put(serial, level);
            }
            if (!supported.isEmpty()) {
                receipt.put("status", "available");
                receipt.put("compatible_devices", supported);
                Json.write(receiptFile, receipt);
                return true;
            }
            require(inventory.values().stream().allMatch("device"::equals),
                    "ADB reports offline, unauthorized or otherwise unusable devices; runtime absence cannot be established: " + inventory);
            List<String> installed = emulators.find();
            receipt.put("installed_compatible_emulators", installed);
            require(installed.isEmpty(), "Installed compatible Android AVDs are available: " + installed
                    + ". Start or reconnect an existing emulator; disconnected but usable runtimes cannot be counted as unavailable.");
            String reason = "No compatible online Android device or installed compatible AVD for API " + minimumSdk + "+";
            receipt.put("status", "unavailable");
            receipt.put("reason", reason);
            Json.write(receiptFile, receipt);
            System.out.println("[UNAVAILABLE] connectedAndroidDeviceTest: " + reason + "; permitted during LocalMavenPublisher " + phase + " phase.");
            return false;
        } catch (Exception failure) {
            receipt.put("status", "failed");
            receipt.put("reason", String.valueOf(failure.getMessage()));
            try { Json.write(receiptFile, receipt); }
            catch (IOException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }

    private static List<String> installedCompatibleEmulators(Path adb, int minimumSdk) throws IOException {
        List<Path> sdks = new ArrayList<>();
        Path platformTools = adb.toAbsolutePath().normalize().getParent();
        if (platformTools != null && platformTools.getParent() != null) sdks.add(platformTools.getParent());
        for (String name : List.of("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                Path path = Path.of(value);
                require(path.isAbsolute(), name + " must identify an absolute existing SDK path");
                sdks.add(path);
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        sdks.add(home.resolve("Library/Android/sdk"));
        sdks.add(home.resolve("Android/Sdk"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) sdks.add(Path.of(localAppData).resolve("Android/Sdk"));
        String configuredAvds = System.getenv("ANDROID_AVD_HOME");
        String androidHome = System.getenv("ANDROID_USER_HOME");
        Path avds = configuredAvds != null && !configuredAvds.isBlank() ? Path.of(configuredAvds)
                : androidHome != null && !androidHome.isBlank() ? Path.of(androidHome).resolve("avd") : home.resolve(".android/avd");
        require(avds.isAbsolute(), "Android AVD directory must be absolute");
        Set<String> architectures = switch (System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT)) {
            case "aarch64", "arm64" -> Set.of("arm64-v8a");
            case "amd64", "x86_64" -> Set.of("x86_64", "x86");
            default -> throw new IllegalArgumentException("Cannot establish Android emulator compatibility on this host architecture");
        };
        return findInstalledCompatibleEmulators(sdks.stream().map(Path::normalize).distinct().toList(), avds, architectures, minimumSdk);
    }

    static List<String> findInstalledCompatibleEmulators(List<Path> sdks, Path avds, Set<String> architectures,
                                                         int minimumSdk) throws IOException {
        if (!Files.exists(avds)) return List.of();
        require(Files.isDirectory(avds), "Configured Android AVD location is not a directory: " + avds);
        List<Path> installedSdks = new ArrayList<>();
        for (Path sdk : sdks) {
            Path executable = sdk.resolve("emulator/" + (System.getProperty("os.name").startsWith("Windows") ? "emulator.exe" : "emulator"));
            if (!Files.exists(executable)) continue;
            require(Files.isRegularFile(executable) && Files.isExecutable(executable), "Installed Android emulator is not executable: " + executable);
            installedSdks.add(sdk);
        }
        if (installedSdks.isEmpty()) return List.of();
        List<String> compatible = new ArrayList<>();
        List<Path> indexes;
        try (var files = Files.list(avds)) { indexes = files.filter(path -> path.getFileName().toString().endsWith(".ini")).sorted().toList(); }
        for (Path index : indexes) {
            Properties entry = readProperties(index);
            String path = entry.getProperty("path");
            require(path != null && Path.of(path).isAbsolute(), "AVD index has no valid absolute directory: " + index);
            Path directory = Path.of(path);
            if (!Files.exists(directory)) continue;
            Properties config = readProperties(directory.resolve("config.ini"));
            String target = config.getProperty("target", entry.getProperty("target", ""));
            var api = Pattern.compile("android-([0-9]+)(?:\\.[0-9]+)?").matcher(target);
            require(api.matches(), "Invalid AVD Android target in " + index + ": " + target);
            int level = Integer.parseInt(api.group(1));
            String architecture = config.getProperty("abi.type", "");
            require(ABIS.contains(architecture), "Invalid AVD architecture in " + index + ": " + architecture);
            if (level < minimumSdk || !architectures.contains(architecture)) continue;
            String image = config.getProperty("image.sysdir.1");
            require(image != null && !image.isBlank(), "AVD has no configured system image: " + index);
            for (Path sdk : installedSdks) {
                Path imageDirectory = Path.of(image).isAbsolute() ? Path.of(image) : sdk.resolve(image);
                if (Files.isRegularFile(imageDirectory.resolve("system.img")) && Files.isRegularFile(imageDirectory.resolve("ramdisk.img"))
                        && (Files.isRegularFile(imageDirectory.resolve("kernel-ranchu")) || Files.isRegularFile(imageDirectory.resolve("kernel-ranchu-64")))) {
                    compatible.add(index.getFileName().toString().replaceFirst("\\.ini$", "") + " (API " + level + ", " + architecture + "; SDK " + sdk + ")");
                    break;
                }
            }
        }
        return List.copyOf(compatible);
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        return properties;
    }

    static int execute(Configuration config, String phase, CommandRunner runner) throws Exception {
        Path root = config.project().toAbsolutePath().normalize();
        String requiredAbi = abi(config.target());
        Path binary = root.resolve("build/bin/" + config.target() + "/debugTest/test.kexe");
        Path report = root.resolve("build/test-results/" + config.target() + "DeviceTest");
        Files.createDirectories(report);
        Path logFile = report.resolve("runtime.log");
        Files.writeString(logFile, "");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("target", config.target());
        receipt.put("required_abi", requiredAbi);
        receipt.put("local_publish_phase", phase == null ? "" : phase);
        receipt.put("requested_serial", config.serial() == null ? "" : config.serial());
        receipt.put("status", "running");
        Path receiptFile = report.resolve("runtime-result.json");
        Json.write(receiptFile, receipt);
        try {
            require(Files.isRegularFile(binary), "Linked Android Native test binary is missing: " + binary);
            byte[] payload = Files.readAllBytes(binary);
            JniArtifactVerifier.Binary.elf(payload, config.target().equals("androidNativeArm64") ? 183 : 62, "Android Native test binary");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            receipt.put("binary_sha256", digest);
            receipt.put("binary_bytes", payload.length);
            Device device;
            try {
                device = selectDevice(config.adb().toString(), config.target(), config.serial(), runner);
            } catch (RuntimeUnavailableException unavailable) {
                if (!("check".equals(phase) || "publish".equals(phase)) || config.serial() != null) throw unavailable;
                String message = "[UNAVAILABLE] " + unavailable.getMessage() + "; permitted during LocalMavenPublisher " + phase + " phase.";
                receipt.put("status", "unavailable");
                receipt.put("reason", unavailable.getMessage());
                Json.write(receiptFile, receipt);
                Files.writeString(logFile, message + "\n");
                System.out.println(message);
                return 0;
            }
            receipt.put("serial", device.serial());
            receipt.put("device_primary_abi", device.primaryAbi());
            receipt.put("device_supported_abis", device.supportedAbis());
            String adb = config.adb().toString();
            String boot = successful(runner, List.of(adb, "-s", device.serial(), "shell", "getprop", "sys.boot_completed"), PROBE_TIMEOUT).strip();
            require(boot.equals("1"), "Selected Android device has not completed boot: " + device.serial());
            String api = successful(runner, List.of(adb, "-s", device.serial(), "shell", "getprop", "ro.build.version.sdk"), PROBE_TIMEOUT).strip();
            require(api.matches("[0-9]+") && Integer.parseInt(api) >= 24, "Selected Android device does not meet API 24 minimum: " + api);
            receipt.put("device_api", api);
            // The CI workflow independently hashes this exact device path after the task.
            String remote = "/data/local/tmp/android-system-properties-" + config.target() + "-test";
            successful(runner, List.of(adb, "-s", device.serial(), "push", binary.toString(), remote), Duration.ofSeconds(60));
            successful(runner, List.of(adb, "-s", device.serial(), "shell", "chmod", "700", remote), PROBE_TIMEOUT);
            String remoteHash = AndroidEmulatorEvidence.binaryDigest(successful(runner,
                    List.of(adb, "-s", device.serial(), "shell", "sha256sum", remote), PROBE_TIMEOUT));
            require(digest.equals(remoteHash), "Pushed Android Native test binary differs from the linked binary");
            CommandResult result = runner.execute(List.of(adb, "-s", device.serial(), "shell", remote, "--ktest_logger=TEAMCITY"), Duration.ofMinutes(5));
            String log = result.stdout() + result.stderr();
            Files.writeString(logFile, log);
            System.out.print(log);
            require(result.exitCode() == 0, "Android Native test process failed with exit code " + result.exitCode());
            List<String> cases = verifyRuntimeLog(log);
            receipt.put("status", "passed");
            receipt.put("test_cases", cases);
            receipt.put("tests", cases.size());
            receipt.put("failed", 0);
            receipt.put("skipped", 0);
            Json.write(receiptFile, receipt);
            return 0;
        } catch (Exception failure) {
            receipt.put("status", "failed");
            receipt.put("reason", String.valueOf(failure.getMessage()));
            try { Json.write(receiptFile, receipt); }
            catch (IOException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        }
    }

    private static CommandResult command(Path root, Path scratch, List<String> arguments, Duration timeout) throws Exception {
        Files.createDirectories(scratch);
        Path stdout = Files.createTempFile(scratch, "adb-stdout-", ".log");
        Path stderr = Files.createTempFile(scratch, "adb-stderr-", ".log");
        Process process = null;
        Exception failure = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(arguments).directory(root.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            builder.environment().putAll(Map.of("TMPDIR", scratch.toString(), "TMP", scratch.toString(), "TEMP", scratch.toString()));
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Android command timed out after " + timeout + ": " + arguments);
            }
            return new CommandResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } catch (Exception exception) {
            failure = exception;
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw exception;
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                Files.deleteIfExists(stdout);
                Files.deleteIfExists(stderr);
            } catch (IOException | RuntimeException cleanupFailure) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static CommandRunner recordingRunner(Path project, Path scratch, Path probes) {
        List<Object> results = new ArrayList<>();
        return (command, timeout) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("command", command);
            Exception failure = null;
            try {
                CommandResult actual = command(project, scratch, command, timeout);
                result.put("exit_code", actual.exitCode());
                result.put("stdout", actual.stdout());
                result.put("stderr", actual.stderr());
                return actual;
            } catch (Exception exception) {
                failure = exception;
                result.put("error", exception.toString());
                throw exception;
            } finally {
                results.add(result);
                try { Json.write(probes, results); }
                catch (IOException recordingFailure) {
                    if (failure != null) failure.addSuppressed(recordingFailure);
                    else throw recordingFailure;
                }
            }
        };
    }

    public static int run(String... arguments) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            require(index + 1 < arguments.length && Set.of("--project-dir", "--target", "--adb", "--serial").contains(arguments[index]),
                    "Unknown or incomplete Android Native test argument");
            require(options.put(arguments[index], arguments[index + 1]) == null, "Repeated Android Native test argument");
        }
        require(options.keySet().containsAll(Set.of("--project-dir", "--target", "--adb")),
                "Expected --project-dir, --target and --adb");
        Path project = Path.of(options.get("--project-dir")).toRealPath();
        String target = options.get("--target");
        abi(target);
        Path scratch = project.resolve("build/tmp/android-native-device-tests/" + target);
        Path probes = project.resolve("build/test-results/" + target + "DeviceTest/device-probes.json");
        return execute(new Configuration(project, target, Path.of(options.get("--adb")), options.get("--serial")),
                System.getProperty(PHASE_PROPERTY), recordingRunner(project, scratch, probes));
    }

    public static void main(String[] arguments) throws Exception {
        run(arguments);
    }
}
