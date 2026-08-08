# 本地依赖锁定清单

每次正式构建前，对传入的三个本地 JAR 执行 SHA-256 校验；不匹配时不得发布构建产物。
该清单对应本次兼容层 `1.2.0` 与 Dominion Sword `1.28.1` 的验证基线。

| 依赖 | 版本 | SHA-256 |
| --- | --- | --- |
| Dominion Sword | 1.28.1 | `e0cb1027a31ea83ad4bbdc3f2cdb8eb2a9555b8726d7807bd5f392bcbb88d5de` |
| Ice and Fire CE | 1.2.7 / MC 1.20.1 | `3d680728ba675f63534bce8c89a187ffa2f4ca582f523007bbb13f781f4f461c` |
| Uranus | 2.2.6-bugfix.2 / MC 1.20.1 | `00a868ea93363a3dbc2b5bdc3843692504294d16e94c014b563dafae7e68369f` |

PowerShell 校验示例：

```powershell
Get-FileHash <jar-path> -Algorithm SHA256
```
