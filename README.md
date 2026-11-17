# BnbEmu

PopTag (Crazy Arcade / 爆爆王 / 泡泡堂) Server Emulator, for North America client.

美服泡泡堂服务端模拟器。

⚠ 代码大部分由AI编写，并经过人工审查。

## Usage

Use the dedicated [Launcher](https://github.com/krrr/BnbEmu/releases), which supports fully automated downloading of the source code and client, compilation and launching, and quick updates to the latest version.

使用专用[【启动器】](https://github.com/krrr/BnbEmu/releases)，支持全自动下载源码和客户端并编译启动、快速更新到最新版本。

## Build 

Requires JDK 17+ and Maven 3.9+. Run under project directory:

需要 JDK 17+ 与 Maven 3.9+。在项目根目录执行：

```powershell
mvn compile exec:java
```

The client needs to be built in real-time via the launcher. Enter any username and password to log in, then enter practice mode to play.

新版客户端需要通过启动器实时构建。输入任意账号密码登录，进入练习模式游玩。

## Additional Information

Multiplayer features are not implemented yet; we are currently focusing on single-player development.
The NA server was chosen initially purely because packet-sniffing existing private servers made development easier. We have no plans to change the version.

联机功能尚未实现，目前专注开发单机功能。
最初选美服纯粹是因为能抓包现成私服方便开发，不考虑更换版本了。

## Credits

Initially based on https://github.com/chuichuide/pingu