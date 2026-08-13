# 正式签名

工程不把私钥路径或密码写进 Gradle。手机与 Wear Real 的 release APK 先由构建系统生成，再使用 Android Build Tools 的 `zipalign` 和 `apksigner` 以同一把个人密钥签名。

v0.2 正式手机端与手表 Real 端必须同时满足：

- applicationId 都是 `com.panzhikun.metaldogshower`；
- versionCode 都是 2，versionName 都是 `0.2.0`；
- 使用同一张签名证书；
- 不把带 `.fake` 或 `.debug` 后缀的变体当作正式端；
- mobile / wear 的 `allowBackup=false`、Network Security Config 与 release `debuggable=false` 均保持正确。

同 applicationId + 同签名是 Wear Data Layer 识别配套应用的前提，但不代表两端共享 Keystore 密钥。v0.2 的同一个官方 token 仍由手机和手表各自的 AndroidKeyStore key 分别加密；签名证书只用于应用身份，不用于加密本地 token。

本次个人正式证书 SHA-256：

```text
9E:AD:62:27:9D:D6:B2:53:2E:10:AC:E6:AF:DF:FB:D5:09:AC:88:A0:6E:AC:95:38:91:A2:5D:2A:69:6C:13:35
```

私钥和密码不在工程中，而在单独的 `MetalDogShower-signing-backup.zip`。该备份等同于后续更新身份，必须离线加密保存，不能提交到 Git、网盘或聊天。

每次签名后都应执行：

```bash
apksigner verify --verbose --print-certs <手机正式 APK>
apksigner verify --verbose --print-certs <手表正式 APK>
```

两者的 certificate SHA-256 必须与上面完全一致，并确认没有额外 signer，才可作为一组正式包安装或进行 provisioning。

## v0.2 本轮状态

本轮按用户要求没有把 v0.2 安装到手机或手表，也没有进行真实同步、OTP 或控制。最终 release 产物、外部同钥签名复核与安装验收如果尚未执行，必须在 [TEST_REPORT.md](TEST_REPORT.md) 中保持“待复核/未执行”，不能沿用上一版安装结果冒充 v0.2 结果。
