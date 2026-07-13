# VAGQ 设计进度

> 最后更新: 2026-07-13
>
> 本文以当前源码为准。`vagq-plan.md` 和 `vagq-requirements.md` 描述目标方案，不代表所有功能已经正确实现。

---

## 当前结论

VAGQ core、上游 issue 旁路、MemBlock、LDU/STA、LSQ empty mark、VRF 和 ROB 的结构连线已经存在，代码可以通过 Scala 编译。但当前还不能认为 constant-stride/indexed load/store 已经端到端可用，主要阻塞点是:

- 地址侧、stride 数据侧和 indexed 数据侧生成的 `entryIdx` 都固定为 0，VOQ 尚未真正分配 entry。
- 数据侧生成的 `psrc2` 固定为 0，load merge 可能读取错误的旧 `vd`。
- `SplitCtrl` 发出的 active store `data` 固定为 0，当前 store data 路径不正确。
- 空 entry 同拍收到地址侧和数据侧时，`enterSplit` 没有置 `valid`，entry 会保持 invalid。
- `MergeCtrl.mergeRespValid` 在 pending merge 被 redirect 杀死时没有清除，会阻塞后续 merge read。
- segment、异常、NACK/replay、flush 和多 entry 并发尚无系统验证。

因此当前阶段应定义为: **端到端结构接通，关键 tag/data 和边界状态仍待补全。**

---

## 完成项

- [x] VAGQ 需求与设计方案文档
- [x] VAGQ core: `EntryTable`、`MaskGen`、`AddrGen`、`SplitCtrl`、`MergeCtrl`
- [x] `VAGQMain` standalone 生成入口
- [x] 8-entry、16-byte flow、2 路 active request 的基础结构
- [x] 地址侧从两个 StaIQ 出队路径旁路到 VAGQ
- [x] stride 数据侧从两个整数 STD issue 路径旁路到 VAGQ
- [x] indexed 数据侧从两个 VStd issue pipe OG2 路径旁路到 VAGQ
- [x] active load 与普通 LDA 在 `VAGQDownstreamAdapter` 仲裁后进入 LDU0/1
- [x] active store 与普通 STA 在 `VAGQDownstreamAdapter` 仲裁后进入 STA0/1
- [x] LDU 3 路和 STA 2 路 response 直接返回 VAGQ
- [x] active load 成功数据复用普通 vector load 写口，并使用 byte mask
- [x] VAGQ load 禁止普通 LDU ROB writeback，完成统一由 VAGQ 写回
- [x] LSQ empty mark 接入 LQ/SQ，并返回 ACK/NACK
- [x] merge old-`vd` 的专用 VRF 读口和 non-active byte 专用 VRF 写口
- [x] VAGQ 独立 ROB writeback 端口和异常信息转换
- [x] active/empty 输出使用带 redirect flush 的一项流水寄存
- [x] entry 宽 payload 不做 reset；reset/redirect 只清 `valid`
- [x] 同拍多路异常选择最小 `faultElemIdx`，相同 offset 按 lane 顺序打破平局
- [x] 当前源码通过 `mill -i xiangshan.compile`

---

## 当前数据流

### 上游

```text
StaIQ addr uop --OG1/Region--> VAGQ.addrUop[0:1]

StdIQ stride data uop -------> MemBlock -------> VAGQ.dataUop[0:1]
VStdIQ indexed data uop --OG2/VecRegion-------> VAGQ.dataUop[2:3]
```

- `Region` 识别 strided/indexed vector memory address uop，阻止其继续进入普通 STA/LDU 路径，并在 VAGQ 接受后向 StaIQ 生成延迟到 S2 的成功响应。
- stride data uop 不进入 `StdExeUnit`，`MemBlock` 直接把整数源操作数构造成 `VAGQDataSideUop`。
- indexed data uop 在 VStd issue pipe 读出 `vs2`，OG2 构造成 `VAGQDataSideUop`，并绕过普通 VStd 功能单元。
- 当前三个构造函数都把 `entryIdx` 写成 0；VOQ 分配结果还没有进入这些 bundle。

### VAGQ core

```text
addrUop/dataUop -> EntryTable -> SplitCtrl -> registered active/empty req
                         ^           |                   |
                         |           v                   v
                         +----- bitmap update      LDU/STA/LSQ
                         ^                               |
                         +--------- MergeCtrl <----------+
                                         |
                                  VRF merge / ROB WB
```

- 地址侧和数据侧通过 `entryIdx` 配对；`waitA` 表示等待地址侧，`waitSI` 表示等待数据侧。
- `reqSent/reqAck` 以 16-bit byte bitmap 编码 IDLE/SENT/DONE。
- active 和 empty 路径分别选择最老的可处理 entry；比较顺序为 `robIdx`、`uopIdx`、entry index。
- active lane0 选择最低地址元素，lane1 从剩余 mask 中选择最高地址元素；ordered indexed 只允许单发。
- empty 路径一次发送选中 entry 的全部 pending non-active byte，并转换为 LSQ element mask。
- active 和 empty 输出各经过一项寄存流水；请求进入该流水时置 `reqSent`，redirect 可丢弃寄存中的旧请求。

### 下游

```text
VAGQ active[0] --+-- ordinary LDA0 priority --> LDU0 --+
VAGQ active[1] --+-- ordinary LDA1 priority --> LDU1 --+--> VAGQ lduResp[0:2]
ordinary LDA2 -------------------------------> LDU2 --+

VAGQ active[0] --+-- ordinary STA0 priority --> STA0 --+
VAGQ active[1] --+-- ordinary STA1 priority --> STA1 --+--> VAGQ staResp[0:1]

VAGQ empty --> LSQWrapper --> LoadQueue/StoreQueue --> emptyResp
```

- 普通 LDA/STA 请求优先。只有对应 `issueLda(i)`/`issueSta(i)` 无效时，active lane `i` 才使用 Unit `i`。
- 这不是按 `robIdx` 的全局最老仲裁，active lane0/1 也不能迁移到其他空闲 unit。
- active store 同拍产生 STA 地址请求和 `vagqStdData`，后者复用 `StdExeUnit.vstdIn` 写 SQ data；但当前请求中的 `data` 为 0。
- LDU/STA response 是 `Valid`，无 response buffer；每个 unit 每拍最多返回一路，对应固定的 3+2 输入 lane。

### VRF 与 ROB

- active load 数据由 LDU 成功路径写回普通 vector load 写口，`vagqActiveLoadMask` 将写使能限制到 active byte。
- load 的 non-active byte 由 `MergeCtrl` 读取 `psrc2` 指向的旧 `vd`，按 `elemAgnosticMask` 生成数据，再通过 VAGQ 专用 masked VRF 写口写回。
- `MergeCtrl` 完成或异常后生成 `VAGQWritebackReq`，`MemBlock` 转为专用 `WriteBackRobBundle`，经 `CtrlBlock` 延迟和 flush 过滤后送 ROB。
- ROB writeback fire 后，EntryTable 清除对应 entry 的 `valid`。

---

## 关键实现细节

| 项目 | 当前实现 |
|---|---|
| `VAGQSize` | 8 |
| flow | 16 byte，要求 `VLEN=128` |
| address/data issue width | 2 / 4 |
| active request width | 2 |
| response width | 3 LDU + 2 STA + 1 LSQ empty |
| active entry 选择 | 最老 `robIdx/uopIdx/entryIdx` |
| merge/wb/excp 选择 | `PriorityEncoder`，低 entry index 优先 |
| ordered indexed | 有 active request 未 ACK 时禁止继续发 active；lane1 禁止 |
| NACK | `clearReqSent`，回到可重发状态 |
| response 匹配 | `entryIdx` 命中且 live entry 的 `robIdx` 相同 |
| redirect | 清 entry `valid`；过滤候选；flush active/empty 输出流水 |
| entry reset | 仅 `entryValid` reset，宽 payload 不 reset |

---

## 未完成与风险

### P0 正确性

1. 实现 VOQ entry 分配，并把不同的 `entryIdx` 同时送到地址侧、stride 数据侧和 indexed 数据侧。
2. 正确生成 `psrc2`，区分 load old-`vd` 和 store data 源寄存器。
3. 补全 active store data。当前 `SplitCtrl.io.lsuReq.bits.data := 0`，写入 SQ 的数据错误。
4. 修复空 entry 同拍地址/数据配对时 `valid` 未置位。
5. 修复 redirect 杀死 pending merge response 后 `mergeRespValid` 不释放的问题。
6. 明确并验证一条 vector memory 指令各 uop 的 LQ/SQ 预留数量，以及 `lqIdx/sqIdx + elemIdx` 的边界和 segment 语义。

### P1 协议与功能

1. 为 indexed OG2 data uop 建立真实 backpressure 保证。当前接口是 `Decoupled`，但 IssuePipe 只用 `XSError` 检查 `ready`，不能停住 OG2。
2. 检查地址侧 StaIQ 成功反馈与 VAGQ 接收、redirect 同拍竞争。
3. 验证 LDU/STA `Valid` response 在所有 replay/exception/flush 情况下不会重复或遗漏。
4. 完成 segment `nf` 的地址、LSQ 索引和数据布局验证；当前 `nf` 主要是透传。
5. 验证非对齐、跨 16B、跨页请求是否始终被设计约束排除；LDU/STA 目前用 assertion 禁止 VAGQ 进入 unaligned path。

### P2 验证与优化

1. 增加 EntryTable、MaskGen、AddrGen、SplitCtrl、MergeCtrl 单元测试。
2. 增加 masked/tail/vstart、ordered indexed、NACK、异常和 redirect 随机测试。
3. 运行 standalone RTL 生成并检查 reset tree、组合环和时序路径。
4. 评估固定 lane 仲裁带来的吞吐损失，再决定是否升级为跨 unit 仲裁。

---

## 相关文件

| 范围 | 文件 |
|---|---|
| VAGQ core | `backend/vector/vagq/{Vagq,EntryTable,MaskGen,AddrGen,SplitCtrl,MergeCtrl,VAGQUtils}.scala` |
| 地址侧上游 | `backend/Region.scala` |
| indexed 数据侧 | `backend/vector/{IssuePipe,VecRegionModule}.scala` |
| 顶层跨区连线 | `backend/Backend.scala` |
| MemBlock 与仲裁 | `mem/MemBlock.scala`, `mem/vector/VAGQDownstreamAdapter.scala` |
| LDU/STA | `mem/pipeline/{Bundles,NewLoadUnit,NewStoreUnit}.scala` |
| LSQ empty mark | `mem/lsqueue/{LSQWrapper,LoadQueue,VirtualLoadQueue,NewStoreQueue,LSQBundle}.scala` |
| ROB | `backend/{BackendParams,CtrlBlock}.scala`, `backend/rob/{Rob,ExceptionGen}.scala` |
