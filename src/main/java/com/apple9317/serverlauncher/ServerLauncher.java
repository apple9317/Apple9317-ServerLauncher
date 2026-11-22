/*
 * Apple9317-ServerLauncher
 * 基于 BHMultiServerLauncher 修改而来
 * 原作者: BlackHoleEra-Team - https://github.com/BlackHoleEra-Team/BHMultiServerLauncher
 * 修改作者: apple9317
 * 许可证: GNU General Public License v3.0
 */
package com.apple9317.serverlauncher;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class ServerLauncher {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat LOG_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    /** 正常关闭服务器时依次发送的命令（不同服务端/代理端的关闭命令不同） */
    private static final String[] GRACEFUL_STOP_COMMANDS = {"stop", "end", "exit"};
    /** 等待服务器响应每条关闭命令的最长时间（毫秒），与 GRACEFUL_STOP_COMMANDS 一一对应 */
    private static final long[] GRACEFUL_STOP_WAITS_MS = {15000L, 10000L, 10000L};
    /** force 停止的二次确认有效期（毫秒） */
    private static final long FORCE_STOP_CONFIRM_WINDOW_MS = 30000L;
    /** 强制结束进程后等待其退出的时间（毫秒） */
    private static final long FORCE_STOP_WAIT_MS = 5000L;

    private volatile Config config;
    private volatile String configPath;
    private ExecutorService executorService;
    private final Map<String, Process> serverProcesses = new ConcurrentHashMap<>();
    private final Map<String, PrintWriter> serverWriters = new ConcurrentHashMap<>();
    private final Set<String> stoppingServers = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    /** quit/exit 已经打印过"正在关闭所有服务器..."，避免关闭钩子重复打印 */
    private volatile boolean shutdownMessagePrinted = false;
    private volatile ForceStopRequest pendingForceStop;
    /** 正在执行 update（重启）的服务器，用于状态展示 */
    private final Set<String> restartingServers = ConcurrentHashMap.newKeySet();
    /** 是否有 update 任务正在执行 */
    private volatile boolean updateInProgress = false;
    /** 每个服务器一把启动锁，保证“是否已运行”的检查与进程登记是原子的 */
    private final Map<String, Object> startLocks = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("请指定配置文件路径: java -jar launcher.jar config.json");
            return;
        }

        new ServerLauncher().startServers(args[0]);
    }

    public void startServers(String configPath) {
        this.configPath = configPath;

        // 读取配置文件
        config = loadConfig(configPath);
        if (config == null) {
            System.err.println("配置解析失败，无法启动服务器");
            return;
        }

        // 创建线程池（用于用户输入处理和服务器启动）
        // 使用缓存线程池：reload 后新增的服务器也能立即获得线程
        executorService = Executors.newCachedThreadPool();

        // 启动用户输入处理
        executorService.submit(this::handleUserInput);

        // 启动所有配置为自动启动的服务器（在单独的线程中）
        if (config.servers.length == 0) {
            System.out.println("配置中没有服务器");
        }
        for (ServerConfig serverConfig : config.servers) {
            if (serverConfig.autorun) {
                // 为每个服务器创建一个新线程
                executorService.submit(() -> {
                    try {
                        startServer(serverConfig);
                    } catch (IOException e) {
                        System.err.println("启动服务器 " + serverConfig.name + " 失败: " + e.getMessage());
                    }
                });

                // 等待一段时间再启动下一个服务器
                try {
                    Thread.sleep(20000); // 20秒延迟
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("启动延迟被中断");
                }
            } else {
                System.out.println("跳过自动启动: " + serverConfig.name + " (autorun=false)");
            }
        }

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdownMessagePrinted) {
                System.out.println("正在关闭所有服务器...");
            }
            running = false;
            executorService.shutdownNow();

            // 关闭所有服务器进程
            for (Process process : serverProcesses.values()) {
                if (process.isAlive()) {
                    process.destroy();
                }
            }
        }));
    }

    private void startServer(ServerConfig serverConfig) throws IOException {
        Object startLock = startLocks.computeIfAbsent(serverConfig.name, key -> new Object());

        Process process;
        PrintWriter processWriter;
        PrintWriter logWriter;

        String logFileName = String.format("%s/%s-%s.log",
                config.logDirectory, serverConfig.name, DATE_FORMAT.format(new Date()));

        // 检查与登记必须在同一把锁内完成，否则 update 与手动 start 可能同时通过“是否已在运行”的检查，
        // 拉起两个进程，其中一个会成为不受管理的孤儿进程
        synchronized (startLock) {
            Process runningProcess = serverProcesses.get(serverConfig.name);
            if (runningProcess != null && runningProcess.isAlive()) {
                System.out.println("[" + serverConfig.name + "] 已经在运行中，忽略重复启动");
                return;
            }

            logWriter = new PrintWriter(new FileWriter(logFileName, true));

            try {
                ProcessBuilder builder = new ProcessBuilder(parseCommand(serverConfig.startCommand));
                builder.directory(new File(serverConfig.workingDirectory));
                builder.redirectErrorStream(true); // 合并标准错误和标准输出

                process = builder.start();
            } catch (IOException | RuntimeException e) {
                logWriter.close();
                throw e;
            }

            stoppingServers.remove(serverConfig.name);
            serverProcesses.put(serverConfig.name, process);

            // 获取进程的输出流用于发送命令
            processWriter = new PrintWriter(process.getOutputStream());
            serverWriters.put(serverConfig.name, processWriter);

            System.out.println("[" + serverConfig.name + "] 服务器已启动");
        }

        try (PrintWriter log = logWriter) {
            // 读取进程输出
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                String timestamp = LOG_TIME_FORMAT.format(new Date());
                String formattedLine = String.format("[%s] [%s] %s", serverConfig.name, timestamp, line);

                // 输出到控制台
                System.out.println(formattedLine);

                // 写入日志文件
                log.println(formattedLine);
                log.flush();
            }

            int exitCode = process.waitFor();
            String exitMessage = String.format("[%s] 服务器进程已退出，代码: %d", serverConfig.name, exitCode);
            System.out.println(exitMessage);
            log.println(exitMessage);

            // 从映射中移除（仅移除本次启动的进程，避免误删 update 重新拉起的新进程）
            serverProcesses.remove(serverConfig.name, process);
            serverWriters.remove(serverConfig.name, processWriter);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[" + serverConfig.name + "] 服务器进程被中断");
        }
    }

    private void handleUserInput() {
        BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

        try {
            System.out.println("Apple9317-ServerLauncher 已启动 - 输入 'help' 查看帮助");

            String line;
            while (running && (line = consoleReader.readLine()) != null) {
                String response = processCommand(line);
                System.out.println(response);

                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    System.exit(0);
                }
            }
        } catch (IOException e) {
            System.err.println("读取用户输入时出错: " + e.getMessage());
        }
    }

    private String processCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "无效命令";
        }

        String[] parts = command.trim().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "help":
                return "可用命令:\n" +
                        "list - 列出所有服务器\n" +
                        "start <服务器> - 启动指定服务器\n" +
                        "stop <服务器> - 发送 stop/end/exit 正常关闭服务器\n" +
                        "stop <服务器> force - 强制结束服务器进程（需要再次输入确认）\n" +
                        "update [服务器|all] - 关闭并重新启动服务器以应用更新（不填则更新全部）\n" +
                        "send <服务器> <命令> - 向指定服务器发送命令\n" +
                        "broadcast <命令> - 向所有服务器发送命令\n" +
                        "status <服务器> - 查看服务器状态\n" +
                        "reload - 重新加载配置文件（不影响正在运行的服务器）\n" +
                        "quit/exit - 退出程序";

            case "list":
                StringBuilder sb = new StringBuilder("服务器列表:\n");
                for (ServerConfig server : config.servers) {
                    Process serverProcess = serverProcesses.get(server.name);
                    String state;
                    if (serverProcess != null && serverProcess.isAlive()) {
                        if (stoppingServers.contains(server.name)) {
                            state = "正在停止";
                        } else if (restartingServers.contains(server.name)) {
                            state = "更新中";
                        } else {
                            state = "运行中";
                        }
                    } else if (restartingServers.contains(server.name)) {
                        state = "更新中";
                    } else {
                        state = "已停止";
                    }
                    sb.append(" - ").append(server.name)
                            .append(" (").append(state)
                            .append(", autorun=").append(server.autorun).append(")\n");
                }
                return sb.toString();

            case "start":
                if (arg.isEmpty()) {
                    return "用法: start <服务器>";
                }

                // 查找要启动的服务器配置
                ServerConfig targetServerConfig = findServerConfig(arg);

                if (targetServerConfig == null) {
                    return "错误: 服务器 '" + arg + "' 不存在";
                }

                if (restartingServers.contains(arg)) {
                    return "错误: 服务器 '" + arg + "' 正在更新中，请等待其重新启动";
                }

                Process existingProcess = serverProcesses.get(arg);
                if (existingProcess != null && existingProcess.isAlive()) {
                    return "错误: 服务器 '" + arg + "' 已经在运行中";
                }

                try {
                    // 创建一个final引用，以便在lambda中使用
                    final ServerConfig finalServerConfig = targetServerConfig;

                    // 在新线程中启动服务器，避免阻塞用户输入
                    executorService.submit(() -> {
                        try {
                            // startServer 会阻塞到进程退出，因此这里不再输出"已启动完成"
                            startServer(finalServerConfig);
                        } catch (IOException e) {
                            System.err.println("启动服务器 " + finalServerConfig.name + " 失败: " + e.getMessage());
                        }
                    });
                    return "正在启动服务器: " + arg;
                } catch (Exception e) {
                    return "错误: 启动服务器 '" + arg + "' 失败: " + e.getMessage();
                }

            case "stop":
                return handleStopCommand(arg);

            case "update":
                return handleUpdateCommand(arg);

            case "send":
                if (arg.isEmpty()) {
                    return "用法: send <服务器> <命令>";
                }

                String[] sendParts = arg.split("\\s+", 2);
                if (sendParts.length < 2) {
                    return "用法: send <服务器> <命令>";
                }

                String targetServer = sendParts[0];
                String serverCommand = sendParts[1];

                if (!serverWriters.containsKey(targetServer)) {
                    return "错误: 服务器 '" + targetServer + "' 不存在或未运行";
                }

                try {
                    PrintWriter writer = serverWriters.get(targetServer);
                    writer.println(serverCommand);
                    writer.flush();
                    return "已向服务器 '" + targetServer + "' 发送命令: " + serverCommand;
                } catch (Exception e) {
                    return "错误: 发送命令到服务器 '" + targetServer + "' 失败: " + e.getMessage();
                }

            case "broadcast":
                if (arg.isEmpty()) {
                    return "用法: broadcast <命令>";
                }

                int successCount = 0;
                for (String serverName : serverWriters.keySet()) {
                    try {
                        PrintWriter writer = serverWriters.get(serverName);
                        writer.println(arg);
                        writer.flush();
                        successCount++;
                    } catch (Exception e) {
                        System.err.println("向服务器 '" + serverName + "' 广播命令失败: " + e.getMessage());
                    }
                }

                return "已向 " + successCount + " 个服务器广播命令: " + arg;

            case "status":
                if (arg.isEmpty()) {
                    return "用法: status <服务器>";
                }

                Process process = serverProcesses.get(arg);
                if (process == null || !process.isAlive()) {
                    return "服务器 '" + arg + "' 状态: 已停止";
                }

                String status;
                if (stoppingServers.contains(arg)) {
                    status = "正在停止";
                } else if (restartingServers.contains(arg)) {
                    status = "更新中";
                } else {
                    status = "运行中";
                }
                return "服务器 '" + arg + "' 状态: " + status +
                        (process.isAlive() ? " (PID: " + process.pid() + ")" : "");

            case "reload":
                return reloadConfig();

            case "quit":
            case "exit":
                running = false;
                shutdownMessagePrinted = true;
                return "正在关闭所有服务器...";

            default:
                return "未知命令: " + cmd + " - 输入 'help' 查看帮助";
        }
    }

    /**
     * 处理 stop 命令。
     * <p>stop &lt;服务器&gt;：依次发送 stop/end/exit，让服务器自行正常关闭。</p>
     * <p>stop &lt;服务器&gt; force：强制结束进程，需要 30 秒内再次输入同一命令确认。</p>
     */
    private String handleStopCommand(String arg) {
        if (arg.isEmpty()) {
            return "用法: stop <服务器> [force]";
        }

        String[] parts = arg.split("\\s+");
        String serverName = parts[0];
        boolean force = false;

        if (parts.length > 2) {
            return "用法: stop <服务器> [force]";
        }
        if (parts.length == 2) {
            if (!parts[1].equalsIgnoreCase("force")) {
                return "用法: stop <服务器> [force]";
            }
            force = true;
        }

        Process process = serverProcesses.get(serverName);
        boolean alive = process != null && process.isAlive();

        if (!alive) {
            // 清理可能残留的已退出进程
            cleanupServer(serverName);
            return "服务器 '" + serverName + "' 未在运行";
        }

        if (force) {
            return handleForceStop(serverName, process);
        }

        return handleGracefulStop(serverName, process);
    }

    /**
     * 处理 update 命令：关闭并重新启动服务器，用于应用文件更新（插件/jar/配置等）。
     * <p>update：等同于 update all；update &lt;服务器&gt;：更新指定服务器；update all：依次更新全部服务器。</p>
     */
    private String handleUpdateCommand(String arg) {
        if (updateInProgress) {
            return "错误: 已有 update 任务正在进行中，请等待其完成";
        }

        List<ServerConfig> targets = new ArrayList<>();
        String targetLabel;

        if (arg.isEmpty() || arg.equalsIgnoreCase("all")) {
            if (config == null || config.servers == null || config.servers.length == 0) {
                return "配置中没有服务器";
            }
            for (ServerConfig server : config.servers) {
                targets.add(server);
            }
            targetLabel = "全部服务器";
        } else {
            ServerConfig serverConfig = findServerConfig(arg);
            if (serverConfig == null) {
                return "错误: 服务器 '" + arg + "' 不存在";
            }
            targets.add(serverConfig);
            targetLabel = "服务器 '" + arg + "'";
        }

        List<String> names = new ArrayList<>();
        for (ServerConfig server : targets) {
            names.add(server.name);
        }

        updateInProgress = true;
        executorService.submit(() -> restartServers(targets));

        return "开始更新 " + targetLabel + "（共 " + targets.size() + " 个）: " + String.join(", ", names)
                + "\n将依次正常关闭并重新启动，进度见下方输出";
    }

    /**
     * 依次重启 update 的目标服务器：先正常关闭（必要时强制结束），确认旧进程退出后再重新启动。
     */
    private void restartServers(List<ServerConfig> targets) {
        int succeeded = 0;
        int failed = 0;
        try {
            for (int i = 0; i < targets.size(); i++) {
                ServerConfig serverConfig = targets.get(i);
                String serverName = serverConfig.name;
                System.out.println("[" + serverName + "] 开始更新 (" + (i + 1) + "/" + targets.size() + ")");
                restartingServers.add(serverName);

                try {
                    Process process = serverProcesses.get(serverName);
                    if (process != null && process.isAlive()) {
                        if (!stopForRestart(serverName, process)) {
                            failed++;
                            System.out.println("[" + serverName + "] 更新失败: 旧进程无法停止，已跳过启动");
                            continue;
                        }
                    }

                    Process leftover = serverProcesses.get(serverName);
                    if (leftover != null && !leftover.isAlive()) {
                        cleanupServer(serverName);
                    }
                    if (!awaitProcessRecordRemoved(serverName, 10000L)) {
                        System.out.println("[" + serverName + "] 旧进程记录未能及时清理，强制清理后继续");
                        cleanupServer(serverName);
                    }

                    final ServerConfig finalServerConfig = serverConfig;
                    executorService.submit(() -> {
                        try {
                            startServer(finalServerConfig);
                        } catch (IOException e) {
                            System.err.println("启动服务器 " + finalServerConfig.name + " 失败: " + e.getMessage());
                        }
                    });

                    if (!awaitServerRegistered(serverName, 60000L)) {
                        failed++;
                        System.out.println("[" + serverName + "] 更新失败: 服务器未能在 60 秒内完成启动");
                        continue;
                    }

                    succeeded++;
                    System.out.println("[" + serverName + "] 更新完成");
                } finally {
                    restartingServers.remove(serverName);
                }
            }
        } finally {
            updateInProgress = false;
            System.out.println("update 结束: 成功 " + succeeded + " 个，失败 " + failed + " 个");
        }
    }

    /**
     * 更新流程中的关闭：依次发送 stop/end/exit，仍不退出时强制结束进程树。
     *
     * @return 进程是否已退出
     */
    private boolean stopForRestart(String serverName, Process process) {
        stoppingServers.add(serverName);
        try {
            System.out.println("[" + serverName + "] 正在关闭服务器...");
            for (int i = 0; i < GRACEFUL_STOP_COMMANDS.length; i++) {
                if (!process.isAlive()) {
                    System.out.println("[" + serverName + "] 服务器已退出，代码: " + process.exitValue());
                    return true;
                }

                PrintWriter writer = serverWriters.get(serverName);
                if (writer == null) {
                    System.out.println("[" + serverName + "] 无法写入服务器标准输入，改为强制结束");
                    break;
                }

                String stopCommand = GRACEFUL_STOP_COMMANDS[i];
                writer.println(stopCommand);
                writer.flush();
                System.out.println("[" + serverName + "] 已发送命令 '" + stopCommand + "'，等待 "
                        + (GRACEFUL_STOP_WAITS_MS[i] / 1000) + " 秒");

                if (process.waitFor(GRACEFUL_STOP_WAITS_MS[i], TimeUnit.MILLISECONDS)) {
                    System.out.println("[" + serverName + "] 服务器已退出，代码: " + process.exitValue());
                    return true;
                }
            }

            if (!process.isAlive()) {
                return true;
            }

            System.out.println("[" + serverName + "] 服务器未能正常关闭，强制结束进程（PID: " + process.pid() + "）");
            if (killProcessTree(process)) {
                System.out.println("[" + serverName + "] 服务器已强制结束，代码: " + process.exitValue());
                return true;
            }

            System.out.println("[" + serverName + "] 强制结束失败，进程仍在运行（PID: " + process.pid() + "）");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[" + serverName + "] 关闭等待被中断");
            return false;
        } finally {
            stoppingServers.remove(serverName);
        }
    }

    /** 强制结束进程及其派生子进程，返回进程是否已退出 */
    private boolean killProcessTree(Process process) {
        try {
            // 先结束派生的子进程（如通过 .sh/.bat 启动脚本拉起的真正服务端进程），再结束父进程
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return process.waitFor(FORCE_STOP_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 等待已退出的进程从 serverProcesses 中清理掉 */
    private boolean awaitProcessRecordRemoved(String serverName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (serverProcesses.get(serverName) == null) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 等待 update 重新拉起的服务器进程登记到 serverProcesses */
    private boolean awaitServerRegistered(String serverName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Process process = serverProcesses.get(serverName);
            if (process != null && process.isAlive()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private String handleGracefulStop(String serverName, Process process) {
        if (stoppingServers.contains(serverName)) {
            return "服务器 '" + serverName + "' 正在停止中，请等待；如无响应可使用 'stop " + serverName + " force'";
        }

        PrintWriter writer = serverWriters.get(serverName);
        if (writer == null) {
            return "错误: 无法向服务器 '" + serverName + "' 发送命令（输入流不可用），可使用 'stop " + serverName + " force'";
        }

        stoppingServers.add(serverName);

        // 在后台线程中发送关闭命令并等待退出，避免阻塞控制台输入
        executorService.submit(() -> {
            try {
                System.out.println("[" + serverName + "] 开始正常关闭，依次发送: "
                        + String.join("/", GRACEFUL_STOP_COMMANDS));
                for (int i = 0; i < GRACEFUL_STOP_COMMANDS.length; i++) {
                    if (!process.isAlive()) {
                        break;
                    }

                    String stopCommand = GRACEFUL_STOP_COMMANDS[i];
                    writer.println(stopCommand);
                    writer.flush();
                    System.out.println("[" + serverName + "] 已发送命令 '" + stopCommand + "'，等待 "
                            + (GRACEFUL_STOP_WAITS_MS[i] / 1000) + " 秒");

                    if (process.waitFor(GRACEFUL_STOP_WAITS_MS[i], TimeUnit.MILLISECONDS)) {
                        System.out.println("[" + serverName + "] 服务器已退出，代码: " + process.exitValue());
                        return;
                    }
                }

                if (process.isAlive()) {
                    System.out.println("[" + serverName + "] 服务器未能正常关闭，"
                            + "可使用 'stop " + serverName + " force' 强制关闭");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[" + serverName + "] 关闭等待被中断");
            } catch (Exception e) {
                System.out.println("[" + serverName + "] 正常关闭失败: " + e.getMessage());
            } finally {
                stoppingServers.remove(serverName);
            }
        });

        return "正在通知服务器 '" + serverName + "' 正常关闭 (stop -> end -> exit)";
    }

    private String handleForceStop(String serverName, Process process) {
        long now = System.currentTimeMillis();
        ForceStopRequest pending = pendingForceStop;

        boolean confirmed = pending != null
                && pending.serverName.equals(serverName)
                && now - pending.requestedAt <= FORCE_STOP_CONFIRM_WINDOW_MS;

        if (!confirmed) {
            pendingForceStop = new ForceStopRequest(serverName, now);
            return "警告: 强制关闭会直接杀死服务器 '" + serverName + "' 的进程（PID: " + process.pid()
                    + "），未保存的数据可能丢失。\n"
                    + "如确认，请在 " + (FORCE_STOP_CONFIRM_WINDOW_MS / 1000) + " 秒内再次输入: stop "
                    + serverName + " force";
        }

        pendingForceStop = null;
        return forceStop(serverName, process);
    }

    private String forceStop(String serverName, Process process) {
        stoppingServers.add(serverName);
        try {
            if (killProcessTree(process)) {
                int exitCode = process.exitValue();
                cleanupServer(serverName);
                return "服务器 '" + serverName + "' 已被强制关闭，代码: " + exitCode;
            }

            return "已发送强制结束信号，但服务器 '" + serverName
                    + "' 的进程仍在运行，请手动检查（PID: " + process.pid() + "）";
        } finally {
            stoppingServers.remove(serverName);
        }
    }

    private String reloadConfig() {
        String path = configPath;
        if (path == null) {
            return "错误: 未记录配置文件路径，无法重新加载";
        }

        Config oldConfig = config;
        Config newConfig = loadConfig(path);
        if (newConfig == null) {
            return "错误: 重新加载配置失败，继续使用原配置";
        }
        config = newConfig;

        Set<String> oldNames = new LinkedHashSet<>();
        if (oldConfig != null && oldConfig.servers != null) {
            for (ServerConfig server : oldConfig.servers) {
                oldNames.add(server.name);
            }
        }
        Set<String> newNames = new LinkedHashSet<>();
        for (ServerConfig server : newConfig.servers) {
            newNames.add(server.name);
        }

        List<String> lines = new ArrayList<>();
        lines.add("配置已重新加载: " + path);
        lines.add("服务器数量: " + newConfig.servers.length + "，日志目录: " + newConfig.logDirectory);

        List<String> added = new ArrayList<>();
        for (String name : newNames) {
            if (!oldNames.contains(name)) {
                added.add(name);
            }
        }
        if (!added.isEmpty()) {
            lines.add("新增服务器: " + String.join(", ", added));
        }

        List<String> removed = new ArrayList<>();
        for (String name : oldNames) {
            if (!newNames.contains(name)) {
                removed.add(name);
            }
        }
        if (!removed.isEmpty()) {
            lines.add("已移除服务器: " + String.join(", ", removed));
        }

        List<String> runningNames = new ArrayList<>();
        for (Map.Entry<String, Process> entry : serverProcesses.entrySet()) {
            if (entry.getValue().isAlive()) {
                runningNames.add(entry.getKey());
            }
        }
        if (!runningNames.isEmpty()) {
            lines.add("运行中的服务器不受影响: " + String.join(", ", runningNames));
        }

        List<String> removedButRunning = new ArrayList<>(removed);
        removedButRunning.retainAll(runningNames);
        if (!removedButRunning.isEmpty()) {
            lines.add("注意: " + String.join(", ", removedButRunning)
                    + " 已从配置中移除但仍处于运行状态，需要停止请直接使用 stop 命令");
        }

        return String.join("\n", lines);
    }

    private Config loadConfig(String path) {
        try {
            Config loaded = Config.fromJson(readConfigFile(path));
            if (loaded == null) {
                return null;
            }
            // 创建日志目录
            new File(loaded.logDirectory).mkdirs();
            return loaded;
        } catch (IOException e) {
            System.err.println("读取配置文件失败: " + e.getMessage());
            return null;
        }
    }

    private String readConfigFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));

        // 跳过 UTF-8 BOM
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }

        // 优先按 UTF-8 解码，失败时回退到系统默认编码
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset);
        }
    }

    private ServerConfig findServerConfig(String name) {
        if (config == null || config.servers == null) {
            return null;
        }
        for (ServerConfig server : config.servers) {
            if (server.name.equals(name)) {
                return server;
            }
        }
        return null;
    }

    private void cleanupServer(String serverName) {
        serverProcesses.remove(serverName);
        serverWriters.remove(serverName);
        stoppingServers.remove(serverName);
    }

    private String[] parseCommand(String command) {
        // 简单的命令解析，支持带引号的参数
        return command.split(" (?=([^\"]*\"[^\"]*\")*[^\"]*$)");
    }

    // 待确认的 force 关闭请求
    private static class ForceStopRequest {
        final String serverName;
        final long requestedAt;

        ForceStopRequest(String serverName, long requestedAt) {
            this.serverName = serverName;
            this.requestedAt = requestedAt;
        }
    }

    // 配置类
    static class Config {
        String logDirectory;
        ServerConfig[] servers;

        static Config fromJson(String json) {
            try {
                JSONObject jsonObject = new JSONObject(json);
                Config config = new Config();

                config.logDirectory = jsonObject.getString("logDirectory");

                JSONArray serversArray = jsonObject.getJSONArray("servers");
                config.servers = new ServerConfig[serversArray.length()];

                for (int i = 0; i < serversArray.length(); i++) {
                    JSONObject serverObj = serversArray.getJSONObject(i);
                    ServerConfig serverConfig = new ServerConfig();

                    serverConfig.name = serverObj.getString("name");
                    serverConfig.workingDirectory = serverObj.getString("workingDirectory");
                    serverConfig.startCommand = serverObj.getString("startCommand");
                    serverConfig.autorun = serverObj.optBoolean("autorun", true); // 默认为true

                    config.servers[i] = serverConfig;
                }

                return config;
            } catch (Exception e) {
                System.err.println("解析 JSON 配置时出错: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }
    }

    // 服务器配置类
    static class ServerConfig {
        String name;
        String workingDirectory;
        String startCommand;
        boolean autorun = true; // 默认为true
    }
}
