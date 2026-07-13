# VAGQ IO 与当前实现说明

> 最后更新: 2026-07-13
>
> 对应实现: `src/main/scala/xiangshan/backend/vector/vagq/`、`src/main/scala/xiangshan/mem/vector/VAGQDownstreamAdapter.scala`

---

## 0. 文档范围

本文描述当前源码中的实际接口和行为，包括:

- VAGQ core: `VAGQEntryTable`、`MaskGen`、`AddrGen`、`SplitCtrl`、`MergeCtrl`
- 上游: StaIQ 地址侧、整数 STD stride 数据侧、VStdIQ indexed 数据侧
- 下游: LDU、STA/STD、LSQ empty mark
- VRF: active load 普通写口、non-active merge 专用读写口
- ROB: VAGQ 独立 writeback 口

当前代码已经完成结构连线，但 `entryIdx`、`psrc2` 和 active store data 仍有未完成项。接口存在不等于对应指令已经端到端正确执行，完整状态见 `progress.md`。

---

## 1. 参数

定义位置: `Vagq.scala`

| 常量 | 当前值 | 含义 |
|---|---:|---|
| `VAGQSize` | 8 | entry 数量 |
| `VAGQEntryIdxWidth` | 3 | entry index 宽度 |
| `FlowBytes` | 16 | 每个 VAGQ uop slice 的 byte 数 |
| `FlowByteWidth` | 4 | flow 内 byte offset 宽度 |
| `UvlByteWidth` | 5 | 0 到 16 byte 的计数宽度 |
| `UopIdxWidth` | 3 | 指令内 uop slice index 宽度 |
| `FaultVstartWidth` | 7 | `uopIdx` 与 flow 内 fault offset 转换后的宽度 |
| `EewWidth` | 2 | EEW 编码，0/1/2/3 对应 8/16/32/64 bit |
| `AlignedTypeWidth` | 3 | LDU/STA 请求宽度编码 |
| `NfWidth` | 3 | segment `nf` 字段宽度 |
| `ExceptionNumberWidth` | 6 | 异常号宽度 |
| `AddrIssueWidth` | 2 | 地址侧输入 lane 数 |
| `DataIssueWidth` | 4 | 数据侧输入 lane 数，2 路 STD + 2 路 VStd |
| `ActiveIssueWidth` | 2 | active LSU 请求 lane 数 |
| `LduRespWidth` | 3 | LDU response lane 数 |
| `StaRespWidth` | 2 | STA response lane 数 |
| `MergeRespWidth` | 6 | 3 LDU + 2 STA + 1 LSQ empty |
| `SplitUpdateWidth` | 2 | active 与 empty 两路 bitmap update |

硬约束:

- `VLEN == 128`
- `VDataBytes == FlowBytes == 16`
- `VAGQSize == 4 || VAGQSize == 8`
- 当前 `SplitCtrl` 实际按两路 active request 编写，不能只改常量扩展宽度

---

## 2. 公共 Bundle

### 2.1 `VAGQMeta`

随地址侧 uop 保存，用于 LSQ 定位、ROB 写回和调试。

| 字段 | 类型 | 含义 |
|---|---|---|
| `pc` | `UInt(VAddrBits.W)` | 指令 PC |
| `isRVC` | `Bool` | 是否为压缩指令 |
| `ftqPtr` | `FtqPtr` | FTQ 指针 |
| `ftqOffset` | `UInt` | fetch block 内偏移 |
| `lqIdx` | `LqPtr` | 当前 uop 的 LQ base pointer |
| `sqIdx` | `SqPtr` | 当前 uop 的 SQ base pointer |
| `trigger` | `TriggerAction` | trigger 信息 |
| `perfDebugInfo` | `PerfDebugInfo` | 性能调试信息 |
| `debug_seqNum` | `InstSeqNum` | debug/difftest 序号 |

### 2.2 `VAGQAddrSideUop`

地址、控制和 mask 侧输入。

| 字段 | 含义 |
|---|---|
| `meta` | `VAGQMeta` |
| `entryIdx` | 目标 VAGQ entry |
| `uopType` | stride/indexed、load/store、ordered 类型 |
| `robIdx` | ROB pointer |
| `pdest` | load 目标向量物理寄存器 |
| `baseAddr` | `x[rs1]` base address |
| `uvlByte` | 当前 16B slice 内属于 `vl` 范围的 byte 数 |
| `vstart`, `useVstart` | prestart mask 输入 |
| `vm`, `v0Mask` | mask active 输入 |
| `deew`, `ieew` | data/index EEW 编码 |
| `vma`, `vta` | agnostic 策略 |
| `uopIdx` | 当前 slice 序号 |
| `nf` | segment field，当前主要透传 |

当前 `buildVagqAddrUop` 从 `NewExuInput` 构造这些字段，但 `entryIdx := 0.U` 仍是 TODO。

### 2.3 `VAGQDataSideUop`

| 字段 | 类型 | 含义 |
|---|---|---|
| `entryIdx` | `UInt(3.W)` | 与地址侧配对的 entry |
| `robIdx` | `RobPtr` | 用于 redirect 与配对检查 |
| `op2Data` | `UInt(128.W)` | stride 值或 indexed offset vector |
| `psrc2` | `UInt(VfPhyRegIdxWidth.W)` | load merge 读取旧 `vd` 的物理源；设计上也可用于 store data 源 |

当前 stride/indexed 两个构造函数都写 `entryIdx := 0.U` 和 `psrc2 := 0.U`。

### 2.4 `VAGQLsuReq`

active 元素的真实访存请求。

| 字段 | 含义 |
|---|---|
| `entryIdx`, `robIdx` | response 返回时定位并校验 live entry |
| `isLoad`, `isStore` | 请求类型，必须二选一 |
| `lqIdx`, `sqIdx` | 当前元素对应的 LSQ pointer，`base + elemIdx` |
| `byteOffset` | 16B flow 内元素起始 byte offset |
| `elemIdx` | flow 内元素 index，`byteOffset >> deew` |
| `mask` | 当前元素覆盖的 byte mask |
| `alignedType` | 当前由 zero-extended `deew` 生成 |
| `vaddr` | `AddrGen` 结果 |
| `data` | active store data；当前 `SplitCtrl` 固定输出 0，尚未完成 |
| `pdest` | active load 目标向量物理寄存器 |
| `nf` | segment field |

### 2.5 `VAGQLsqEmptyReq/Resp`

non-active 元素不进入 AddrGen/LDU/STA，而是通知 LSQ 消费预留项。

Request 字段:

| 字段 | 含义 |
|---|---|
| `entryIdx`, `robIdx` | VAGQ response tag |
| `isLoad`, `isStore` | 选择 LQ 或 SQ |
| `lqIdx`, `sqIdx` | LSQ base pointer |
| `emptyMask` | VAGQ 的 16-bit byte mask，response 原样返回用于 bitmap update |
| `entryMask` | 按 `deew` 将 byte mask 压缩后的 LSQ element mask |

Response 字段:

| 字段 | 含义 |
|---|---|
| `entryIdx`, `robIdx`, `isLoad`, `isStore` | request tag 回传 |
| `mask` | request 的 `emptyMask` |
| `isNACK` | LSQ match/mark 失败，需要 VAGQ 重发 |
| `exception`, `exceptionNumber` | 当前 empty mark 固定不产生异常 |

### 2.6 `VAGQResp`

LDU/STA response 和 VAGQ 内部转换后的 empty response 使用同一格式。

| 字段 | 含义 |
|---|---|
| `entryIdx`, `robIdx` | response tag |
| `isLoad`, `isStore` | 来源类型 |
| `byteOffset`, `mask` | 当前元素位置和覆盖 byte |
| `data` | LDU 对齐后的 load data；MergeCtrl 当前不收集该字段 |
| `isNACK` | 清对应 `reqSent`，允许重发 |
| `exception`, `exceptionNumber` | 记录异常并进入 `excp` |

### 2.7 VRF Bundle

| Bundle | 字段 | 说明 |
|---|---|---|
| `VAGQVRFReadReq` | `entryIdx`, `robIdx`, `psrc` | merge 读取旧 `vd` |
| `VAGQVRFReadResp` | `entryIdx`, `robIdx`, `data` | 一拍后返回 128-bit VRF 数据 |
| `VAGQVRFWriteReq` | `entryIdx`, `pdest`, `data`, `mask` | 写 load non-active byte |

这些接口都是 `Valid`，没有 `ready`。当前 VecRegion 为它们配置了独立读写端口。

### 2.8 `VAGQWritebackReq`

| 字段 | 含义 |
|---|---|
| `meta`, `entryIdx`, `robIdx` | ROB/LSQ/debug tag |
| `exception`, `exceptionNumber` | 异常状态 |
| `faultElemIdx` | 当前 16B flow 内的 fault byte offset |
| `faultVstart` | 转换后的架构元素序号 |
| `uopType`, `uopIdx`, `deew`, `nf` | vector memory exception 信息 |

### 2.9 `VAGQMemPipelineMeta`

Adapter 向 LDU/STA pipeline 旁带的 metadata。

| 字段 | 含义 |
|---|---|
| `valid` | pipeline request 是否来自 VAGQ |
| `entryIdx`, `robIdx` | response tag |
| `isLoad`, `isStore` | 请求类型 |
| `byteOffset`, `mask` | active 元素位置和 byte mask |

普通 LDU/STA 请求的该 bundle 全 0。

---

## 3. `VAGQ` 顶层 IO

| 信号 | 方向/协议 | 宽度 | 当前用途 |
|---|---|---:|---|
| `addrUop` | input `Decoupled` | 2 | 地址侧输入 |
| `dataUop` | input `Decoupled` | 4 | stride/indexed 数据侧输入 |
| `lsuReq` | output `Decoupled` | 2 | registered active request |
| `lduResp` | input `Valid` | 3 | LDU ACK/NACK/exception |
| `staResp` | input `Valid` | 2 | STA ACK/NACK/exception |
| `lsqEmptyReq` | output `Decoupled` | 1 | registered empty mark request |
| `lsqEmptyResp` | input `Valid` | 1 | empty mark ACK/NACK |
| `vrfReadReq` | output `Valid` | 1 | merge old-`vd` read |
| `vrfReadResp` | input `Valid` | 1 | old-`vd` read response |
| `vrfWriteReq` | output `Valid` | 1 | non-active masked write |
| `robWriteback` | output `Decoupled` | 1 | complete/exception writeback |
| `redirect` | input `Valid` | 1 | flush/filter |

顶层行为:

- `EntryTable.entries` 同时送给 `SplitCtrl` 和 `MergeCtrl`。
- `SplitCtrl` 的 active/empty 输出分别经过 `NewPipelineConnect` 一项寄存器，再成为顶层输出。
- 寄存请求通过其 `robIdx.needFlush(redirect)` 精确 flush；redirect 当拍屏蔽输出 `valid`。
- `splitUpdate` 在请求进入输出寄存器时更新 `reqSent`，不是等 LDU/STA 最终返回后才更新。
- empty response 转成 `VAGQResp` 后进入 MergeCtrl。
- 当前 VRF read 只来自 MergeCtrl；源码中的顶层注释仍提到 store read，但 SplitCtrl 已没有 VRF read 接口。

---

## 4. Core 内部模块

### 4.1 `VAGQEntryTable`

IO:

| 信号 | 协议 | 说明 |
|---|---|---|
| `addrUop[2]` | input `Decoupled` | 写地址、控制和 mask 字段 |
| `dataUop[4]` | input `Decoupled` | 写 `op2Data/psrc2` |
| `entries[8]` | output | 给 Split/Merge 的快照 |
| `splitUpdate[2]` | input `Valid` | 设置 active/empty `reqSent` |
| `mergeReqUpdate[6]` | input `Valid` | ACK/NACK/exception 更新 |
| `mergeStateUpdate` | input `Valid` | 状态推进或 clearValid |
| `redirect` | input `Valid` | 清除被 flush entry |

ready:

```text
addr.ready = idxValid && (!valid || state == waitA)  && !entryFlush
data.ready = idxValid && (!valid || state == waitSI) && !entryFlush
```

状态:

| 状态 | 含义 |
|---|---|
| `valid=0` | free；其他 payload/state 都是 don't-care |
| `waitA` | 已有 data side，等待 address side |
| `waitSI` | 已有 address side，等待 stride/index data side |
| `split` | 拆分并等待 active/empty response |
| `merge` | load non-active byte merge |
| `wb` | 正常 ROB writeback |
| `excp` | 等待异常 ROB writeback |

存储优化:

- 宽 payload 使用无 reset 的 `Reg(Vec(...))`。
- `entryValid` 独立 `RegInit(false)`。
- reset 和 redirect 只清 `valid`，不清整个 entry。
- 所有 Split/Merge 候选都先检查 `valid`，无效 payload 不参与行为。

更新顺序为 bitmap update、state update、enqueue、flush；flush 最后覆盖 `valid`。

同拍多 response:

- `setReqSent/clearReqSent/setReqAck` 分别按 mask OR 合并。
- 多路 exception 命中同一 entry 时选择最小 `faultElemIdx`。
- fault offset 相同时，`PriorityMux` 按 response lane 顺序确定结果。

当前风险:

- 两个 addr lane 命中同一 entry、任意两个 data lane 命中同一 entry会触发 `XSError`。
- 空 entry 同拍收到 addr+data 时调用 `enterSplit`，但该 helper 当前没有置 `valid := true`。

### 4.2 `MaskGen`

每个地址输入 lane 对应一个组合 `MaskGen`，地址 uop fire 时将结果写入 entry。

对 byte `i`:

```text
prestart = i < uvstartByte
tail     = !prestart && i >= uvlByte
masked   = !vm && !v0[element(i)]
active   = !prestart && !tail && !masked
```

- `elemActiveMask` 标记真实访存 byte。
- `elemAgnosticMask` 只标记 `vma` 生效的 masked-off byte 和 `vta` 生效的 tail byte。
- prestart byte 始终不 agnostic，merge 时保留旧 `vd`。
- mask 在 entry 入队时一次生成，Split/Merge 不再读取 `vm/v0`。

### 4.3 `AddrGen`

每条 active lane 各有一个组合 AddrGen。

```text
elemIdx = byteOffset >> deew
elemOrd = (uopIdx << elemNum(deew)) | elemIdx
```

其中 `elemNum(deew)` 返回每个 128-bit slice 所含元素数量的 log2: 4/3/2/1。

Stride:

```text
offset = signed(op2Data[XLEN-1:0]) * signed(elemOrd)
vaddr  = baseAddr + offset
```

Indexed:

- 按 `ieew` 从 `op2Data` 选择 8/16/32/64-bit index。
- 8/16/32-bit index zero-extend 到 XLEN；64-bit 原样使用。
- `vaddr = baseAddr + indexOffset`。

### 4.4 `SplitCtrl`

候选 mask:

```text
canSplit     = valid && state == split && !needFlush
activePending = canSplit && !orderedBlocked ? (~reqSent & elemActiveMask) : 0
emptyPending  = canSplit ? (~reqSent & ~reqAck & ~elemActiveMask) : 0
```

entry 选择:

- active 和 empty 各自独立选择 entry，可以同拍来自不同 entry。
- `oldestEntryOH` 先比较 `robIdx`，同 ROB 比较 `uopIdx`，最后以低 entry index 打破平局。

active 选择:

- lane0 选择最低 set bit 所在元素。
- lane1 从去掉 lane0 元素后的 mask 中选择最高 set bit 所在元素。
- offset 先按 `deew` 对齐到元素起始 byte，issue mask 覆盖整个元素。
- ordered indexed 在已有 active request 未 ACK 时阻止继续发射，同时禁止 lane1。
- 当前两路 active 来自同一个 selected entry。

empty 选择:

- 一拍最多发送一个 entry 的 empty request。
- 一个 empty request 可以包含该 entry 全部 pending non-active byte。
- `byteMaskToEntryMask` 按 EEW 将 16-bit byte mask 压缩为 16/8/4/2 个 LSQ element bit。

bitmap update:

- 任一 active lane fire 时，`update(0)` OR 两路 issue mask 并设置 `reqSent`。
- empty request fire 时，`update(1)` 设置整个 `emptyMask` 的 `reqSent`。

当前 `VAGQLsuReq.data` 固定为 0，因此 active store data 尚未实现。

### 4.5 `MergeCtrl`

response 接受:

```text
accepted = resp.valid && entryIdx in range &&
           entries(entryIdx).valid && entries(entryIdx).robIdx == resp.robIdx
```

response 映射:

| response | 更新 |
|---|---|
| ACK | `setReqAck |= mask` |
| NACK 且无异常 | `clearReqSent |= mask` |
| exception | `setReqAck |= mask`，记录异常，状态进入 `excp` |

候选和推进:

- `splitDone`: `state==split && reqAck.andR`，并排除同拍异常命中的 entry。
- store 或 active mask 全 1 的 load 从 split 直接进入 `wb`。
- 其他 load 进入 `merge`。
- merge/wb/excp/splitDone 各自通过 `PriorityEncoder` 选择，低 entry index 优先，不是 oldest 选择。
- 状态更新优先级: splitDone > VRF merge write > ROB writeback fire。

load merge:

1. `merge` entry 产生 `VAGQVRFReadReq(psrc2)`。
2. VecRegion 一拍后带 `entryIdx+robIdx` 返回旧 `vd`。
3. `MergeCtrl` 缓存 response data。
4. `nonActiveMask = ~elemActiveMask`。
5. agnostic non-active byte 写全 1，其余 non-active byte写旧 `vd`。
6. 专用 VRF 写口只使能 `nonActiveMask`，不会覆盖 LDU 已写 active byte。

异常:

- `faultElemIdx` 保存 response 的 `byteOffset`。
- `faultVstart = (uopIdx << elemNum(deew)) + (faultElemIdx >> deew)`。
- exception entry 只有在 fault 之前已发送但未 ACK 的 byte 清空后才允许 ROB writeback。

当前风险:

- `mergeRespValid` 的清除条件当前写成 `!mergeRespValid || vrfWriteValid`。pending merge 被 redirect 杀死后 `vrfWriteValid=0`，该 valid 可能永久保持，阻塞新 merge。

---

## 5. 上游接入

### 5.1 地址侧

路径:

```text
StaIQ -> bypass/data path -> Region.toMem register -> buildVagqAddrUop -> Backend -> MemBlock -> VAGQ
```

- 仅 `isVagqAddrUop` 的 strided/indexed vector load/store 被截获。
- 被截获 uop 不再送普通 MemBlock issue input。
- VAGQ ready 时 fire，Region 生成 delayed S2 `finalSuccess` 释放 StaIQ entry。
- VAGQ 不 ready 时 S0 response 失败，IQ 保留并重试。
- 两个 StaIQ 分别对应 `addrUop(0/1)`，不做跨 lane 仲裁。

### 5.2 Stride 数据侧

路径:

```text
StdIQ -> MemBlock issueStd[i] -> buildVagqStrideDataUop -> VAGQ.dataUop[i]
```

- stride data uop 使用整数源 `src(0)` 作为 `op2Data`。
- 它绕过普通 `StdExeUnit.io.in`。
- `stdIssue.ready` 直接由对应 VAGQ data lane ready 控制。

### 5.3 Indexed 数据侧

路径:

```text
Sta dispatch copy -> VStdIQ -> IssuePipe OG0/OG1 read vs2 -> OG2 -> VecRegion -> Backend -> MemBlock -> VAGQ.dataUop[2:3]
```

- Region 将 indexed data uop 的 VStd 源改为 `vs2`。
- IssuePipe 在 IS2/OG2 识别 indexed VAGQ data uop，构造 `VAGQDataSideUop` 并禁止其进入普通 VStd EX0。
- VecRegion 输出类型是 `Decoupled`，但当前 IssuePipe 没有 ready 回传，只用 `XSError(valid && !ready)` 检查，因此依赖上游保证 VAGQ entry 可接受。

### 5.4 当前 tag 缺口

设计要求 VOQ 为地址侧和数据侧分配相同且独占的 `entryIdx`。当前代码尚未实现该分配和传播，三个 `buildVagq*Uop` helper 都固定写 entry 0。这会导致:

- 多条指令无法并行使用 8 个 entry。
- 同拍多 lane 可能命中同一 entry 并触发断言。
- 地址侧与数据侧可能按错误的 entry 配对。

---

## 6. `VAGQDownstreamAdapter`

### 6.1 IO

| 信号 | 说明 |
|---|---|
| `vagqLsuReq[2]` | VAGQ active request |
| `vagqLduResp[3]`, `vagqStaResp[2]` | 返回 VAGQ 的 response |
| `issueLda[]`, `lduReq[]`, `lduReqMeta[]` | 普通 LDA 输入、仲裁后 LDU 输入、VAGQ metadata |
| `issueSta[]`, `staReq[]`, `staReqMeta[]` | 普通 STA 输入、仲裁后 STA 输入、VAGQ metadata |
| `stdDataBusy[2]`, `vagqStdData[2]` | 普通 vector store data 占用与 active store data 输出 |
| `vagqLsqEmptyReq/Resp` | VAGQ 侧 empty path |
| `lsqEmptyReq/Resp` | LSQWrapper 侧 empty path |

### 6.2 Load 仲裁

对 `i=0,1`:

```text
selectActive = vagqReq(i).valid && isLoad && !issueLda(i).valid
```

- 普通 `issueLda(i)` 始终优先。
- active lane i 只能使用 LoadUnit i。
- LoadUnit2 只接普通 `issueLda2`。
- 没有 robIdx 比较，也没有从所有请求中选择全局最老 3 个。

选中 active load 后，adapter 构造最小 `ExuInput`:

- `fuType=vldu`
- `fuOpType=vle8/vle16/vle32/vle64`
- `src(0)=vaddr`
- 设置 `robIdx/pdest/lqIdx/sqIdx/vecWen`
- 同拍提供 `VAGQMemPipelineMeta`

### 6.3 Store 仲裁

对 `i=0,1`:

```text
selectActive = vagqReq(i).valid && isStore && !issueSta(i).valid
canFire      = selectActive && !stdDataBusy(i) && staReq(i).ready
```

- 普通 `issueSta(i)` 始终优先。
- active lane i 只能使用 StoreUnit i 和对应 STD data lane i。
- active STA request 与 `vagqStdData` 同拍产生，保证地址和数据一起被接收。
- `stdDataBusy` 当前由普通 `vstdStoreData(i).valid` 驱动。
- `vagqStdData` 进入 `StdExeUnit.vstdIn`，再写 StoreQueue data。
- 当前 `vagqStdData.data` 来自 `VAGQLsuReq.data`，而该字段固定为 0。

### 6.4 Response 与 empty path

- 3 路 LDU 和 2 路 STA response 直接透传，无压缩和 response buffer。
- 每路是 `Valid`，不能反压。
- LSQ empty req/resp 只做 Decoupled/Valid 透传。

---

## 7. LDU/STA 行为

### 7.1 Active load

- S0 将 `vagqReqMeta` 写入 load pipeline bundle。
- VAGQ load 不走普通 vector load ROB writeback。
- replay、RAR、forward match invalid 等条件形成 `isNACK`，由 VAGQ 清 `reqSent` 后重发，不进入普通 LQ replay 流程。
- 成功时 LDU 将 load data 左移 `byteOffset*8` 对齐到 flow，并通过普通 vector load RF 写口写回。
- Backend 使用 `vagqActiveLoadMask` 替换全写 mask，只写当前 active 元素 byte。
- 成功 load 同时更新 LQ address valid。
- LDU 生成带 `entryIdx/robIdx/mask/exception` 的 `VAGQResp`。
- assertion 禁止 VAGQ active load 进入 unaligned split/concat 路径。

### 7.2 Active store

- S0 将 `vagqReqMeta` 写入 store pipeline bundle。
- STA 完成时生成 `VAGQResp`。
- TLB miss 或 RS replay 且无异常时返回 NACK。
- store exception vector 转换为 `exceptionNumber`。
- assertion 禁止 VAGQ active store 进入 unaligned split/concat 路径。
- store data 不经过 STA 本身，由对应 `StdExeUnit.vstdIn` 写入 SQ data。

---

## 8. LSQ empty mark

流程:

```text
SplitCtrl -> registered lsqEmptyReq -> Adapter -> LSQWrapper
                                              +-> LoadQueue.emptyMark
                                              +-> StoreQueue.emptyMark
LSQWrapper -> lsqEmptyResp -> Adapter -> MergeCtrl
```

LSQWrapper 要求 `isLoad` 和 `isStore` 恰好一个为真。

LoadQueue/SQ 对 `entryMask(i)=1` 的目标项检查:

- target entry 已分配并且是 vector entry
- target `robIdx` 匹配
- 记录的 `lqBaseIdx/sqBaseIdx` 与 request base pointer 匹配
- 当前没有 redirect/cancel/dequeue 冲突

全部目标命中才 `emptyMarkSuccess=true`。LSQWrapper 在 request fire 同拍产生 response:

- success: `isNACK=0`
- failure: `isNACK=1`，VAGQ 清 `emptyMask` 对应的 `reqSent` 后重试
- empty mark 不产生架构异常

LoadQueue 成功后将目标项标记 committed；StoreQueue 成功后将目标项标记 `vecInactive`。

---

## 9. VRF 与 ROB

### 9.1 Active load VRF write

active load 不使用 VAGQ 专用 merge 写口，而是复用 LDU 原有 vector load writeback。Backend 将 `VAGQResp.mask` 作为 `Exu.ToRf.mask`，VecRegion 对每个 byte 生成 write enable。

### 9.2 Non-active merge VRF port

VecRegion 在普通 VRF 端口之外增加:

- 1 个 VAGQ read port: request 当拍给 `vpRaddr`，下一拍返回 data 和寄存的 `entryIdx/robIdx`
- 1 个 VAGQ masked write port: `valid && mask(byteIdx)` 直接形成每 byte write enable

接口没有 ready，因此当前实现假设专用端口每拍都能接受。

### 9.3 ROB writeback

路径:

```text
MergeCtrl.robWriteback
  -> MemBlock VAGQWritebackConnect.toRob
  -> Backend.mem.vagqRobWriteback
  -> CtrlBlock delayedNotFlushedVagqWriteBack
  -> ROB.vagqWriteback / ExceptionGen
```

- `BackendParams.vagqWritebackParam` 是 fake ExeUnit param，用于生成独立 `WriteBackRobBundle`。
- bundle 包含可选 `entryIdx`，普通非 VAGQ 写口不依赖它。
- `CtrlBlock` 对该端口做一拍寄存和 older redirect flush 过滤。
- ROB 将 VAGQ writeback 计入 writeback 数，并把异常送 ExceptionGen。
- `MemBlock` 将 `vagq.io.robWriteback.ready` 固定为 true；writeback fire 后 VAGQ entry 被释放。

---

## 10. 协议约束与已知限制

### Decoupled

- `addrUop/dataUop/lsuReq/lsqEmptyReq/robWriteback` 只有 `valid && ready` 才完成传输。
- active/empty 输出的一项寄存器在下游不 ready 时保持请求。
- indexed OG2 data path 虽声明为 Decoupled，目前不能真正反压 IssuePipe。

### Valid

- LDU/STA/LSQ response、VRF read/write 都没有 ready。
- 发送端必须保证接收端每拍能处理全部 lane。
- `MergeRespWidth=6` 允许 3+2+1 response 同拍全部转成 EntryTable update。

### Response tag

所有 active/empty response 必须同时匹配:

```text
entryIdx in range && entry.valid && response.robIdx == entry.robIdx
```

只匹配 `entryIdx` 不足以防止 entry 释放后被新指令复用时的旧 response 污染。

### Bitmap

| 状态 | `reqSent` | `reqAck` |
|---|---:|---:|
| IDLE | 0 | 0 |
| SENT | 1 | 0 |
| DONE | X | 1 |

- request 进入输出流水时设置 `reqSent`
- ACK 设置 `reqAck`
- NACK 清 `reqSent`
- `reqAck.andR` 表示 16B flow 全部完成

### 当前阻塞项

1. VOQ `entryIdx` 分配/传播未实现，所有入口固定 entry 0。
2. `psrc2` 固定 0，old-`vd` 读地址不可靠。
3. active store data 固定 0。
4. 空 entry 同拍 addr+data 不会置 valid。
5. redirect 杀死 pending merge response 时可能卡住 `mergeRespValid`。
6. segment、unaligned、跨页和完整异常顺序尚未验证。
