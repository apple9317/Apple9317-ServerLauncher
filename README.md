# Apple9317-ServerLauncher

一个基于控制台的 Minecraft 多服务器启动与管理工具，专为 MCSM 面板环境设计，可同时管理多个 Minecraft 服务端实例。同时也可在其他运行环境（如VPS）使用

## 项目来源与致谢

本项目基于 [BHMultiServerLauncher](https://github.com/BlackHoleEra-Team/BHMultiServerLauncher) 修改而来，遵循 GPL-3.0 许可证。

- 原作者：[BlackHoleEra-Team](https://github.com/BlackHoleEra-Team/BHMultiServerLauncher)
- 修改作者：apple9317

修改内容：新增 `stop`/`stop <服务器> force`/`reload` 命令，改进进程管理与配置读取，并调整构建与发布流程。

## 功能特点

- 🚀 **多服务器管理**：同时启动和管理多个 Minecraft 服务端
- 📝 **集中式日志**：所有服务器日志统一存储和管理
- ⌨️ **交互式控制台**：提供简洁易用的命令控制界面
- 🔧 **灵活配置**：支持自定义启动参数和工作目录
- ⚡ **自动启动**：支持配置服务器自动启动

## 快速开始

### 安装要求

- Java 运行环境

### 启动方式

```bash
java -jar Apple9317-ServerLauncher.jar servers-config.json
```

## 配置文件说明

创建 `servers-config.json` 文件来配置您的服务器：

```json
{
  "logDirectory": "server-logs",
  "servers": [
    {
      "name": "服务器名称",
      "workingDirectory": "服务器工作目录",
      "startCommand": "启动命令",
      "autorun": true
    }
  ]
}
```

### 配置字段说明

- `logDirectory`: 服务器日志存储目录
- `servers`: 服务器配置数组
  - `name`: 服务器标识名称
  - `workingDirectory`: 服务器文件所在目录
  - `startCommand`: 启动服务器的命令
  - `autorun`: 是否随启动器自动启动

### 配置示例

```json
{
  "logDirectory": "server-logs",
  "servers": [
    {
      "name": "Velocity",
      "workingDirectory": "Velocity",
      "startCommand": "java -Xmx200M -jar Velocity.jar nogui",
      "autorun": true
    },
    {
      "name": "Main-World",
      "workingDirectory": "./",
      "startCommand": "java -Xmx4G -jar leaves.jar nogui",
      "autorun": true
    },
    {
      "name": "Main-World2",
      "workingDirectory": "Main-World2",
      "startCommand": "java -Xmx1750M -jar purpur-1.20.4-2176.jar nogui",
      "autorun": true
    }
  ]
}
```

## 命令列表

| 命令 | 参数 | 描述 |
|------|------|------|
| `list` | 无 | 列出所有已配置的服务器 |
| `start` | `<服务器名称>` | 启动指定的服务器 |
| `stop` | `<服务器名称>` | 依次发送 `stop`/`end`/`exit`，让服务器正常关闭 |
| `stop` | `<服务器名称> force` | 强制结束服务器进程（需二次确认） |
| `send` | `<服务器名称> <命令>` | 向指定服务器发送控制台命令 |
| `broadcast` | `<命令>` | 向所有服务器发送同一命令 |
| `status` | `<服务器名称>` | 查看指定服务器的状态信息 |
| `reload` | 无 | 重新加载配置文件（不影响正在运行的服务器） |
| `quit`/`exit` | 无 | 安全退出启动器程序(请确保全部服务端均已停止) |

## 使用示例

1. **列出所有服务器**:
```
   list
```

2. **启动特定服务器**:
```
   start Velocity
```

3. **向服务器发送命令**:
```
   send Main-World say Hello everyone!
```

4. **广播命令到所有服务器**:
```
   broadcast say Server maintenance in 10 minutes!
```

5. **检查服务器状态**:
```
   status Main-World2
```

6. **正常关闭服务器**:
```
   stop Main-World2
```

7. **强制关闭无响应的服务器**(需要再次输入同一命令确认):
```
   stop Main-World2 force
```

8. **重新加载配置文件**(不会影响正在运行的服务器):
```
   reload
```

## 构建

需要 JDK 21+ 与 Maven。`pom.xml` 中已把 `maven-assembly-plugin` 绑定到 `package` 阶段，因此每次构建都会自动额外生成带依赖的 jar：

```bash
mvn package
```

产物：

- `target/Apple9317-ServerLauncher-1.2.1.jar` —— 普通 jar，运行时需要额外提供 `org.json`
- `target/Apple9317-ServerLauncher-1.2.1-jar-with-dependencies.jar` —— 内含 `org.json`，可直接运行：

```bash
java -jar target/Apple9317-ServerLauncher-1.2.1-jar-with-dependencies.jar servers-config.json
```

没有安装 Maven 时，可在 Git Bash 等 bash 环境中执行 `./build.sh`，脚本会退化为 `javac` + `jar` 的离线打包，产物路径与 Maven 构建一致。

仓库还带有 GitHub Actions 工作流（`.github/workflows/build.yml`）：每次 push 都会自动构建并上传 `jar-with-dependencies` 产物，推送 `v*` 标签时会自动创建 Release 并附加该 jar。

## 注意事项

- 确保所有配置的工作目录存在且包含正确的服务器文件
- 启动命令应使用相对于工作目录的路径
- 退出程序前请确保全部服务器已被关闭！
- 日志文件会自动保存在指定的日志目录中
- `stop <服务器名称>` 会依次发送 `stop`、`end`、`exit`，分别等待 15/10/10 秒；若服务器仍未退出，会提示改用 `force`
- `stop <服务器名称> force` 会直接结束进程及其子进程，未保存的数据可能丢失；需要在 30 秒内再次输入同一命令完成二次确认
- `reload` 会重新读取启动时指定的配置文件；解析失败时保留原配置，已从配置中移除但仍运行的服务器需要手动使用 `stop` 关闭

---

如有问题或建议，请提交 Issue。
