package xiangshan.backend.fu.wrapper

import chisel3._
import chisel3.util.Fill
import org.chipsalliance.cde.config.Parameters
import utility.DelayN
import xiangshan.backend.fu.FuConfig
import xiangshan.backend.fu.vector.{NewMgu, VecPipedFuncUnit}
import yunsuan.vector.v2.Crypto.VSha512msDatapath

class VSha512msWrapper(cfg: FuConfig)(implicit p: Parameters) extends VecPipedFuncUnit(cfg) {
  private val VLENB = VLEN / 8

  private val datapath = Module(new VSha512msDatapath)
  private val mgu = Module(new NewMgu(VLEN))

  datapath.io.uopType := fuOpType(1, 0) // sub-opcode ms0..ms3
  datapath.io.vs1 := vs1
  datapath.io.vs2 := vs2
  datapath.io.vd := oldVd

  mgu.io.in.mask := Fill(VLEN, true.B)
  mgu.io.in.info.ta := vta
  mgu.io.in.info.ma := vma
  mgu.io.in.info.vstart := 0.U
  mgu.io.in.info.vl := vl
  mgu.io.in.info.eew := vsew
  mgu.io.in.info.vsew := vsew
  mgu.io.in.info.vdIdx := vuopIdx
  mgu.io.in.isIndexedVls := false.B

  private val activeEn = mgu.io.out.activeEn
  private val activeEnS1 = DelayN(activeEn, 1)

  private val newVdBytes = Wire(Vec(VLENB, UInt(8.W)))
  private val oldVdBytes = Wire(Vec(VLENB, UInt(8.W)))
  newVdBytes := DelayN(datapath.io.res, 1).asTypeOf(newVdBytes)
  oldVdBytes := outOldVd.asTypeOf(oldVdBytes)

  private val resVecByte = Wire(Vec(VLENB, UInt(8.W)))
  for (i <- 0 until VLENB) {
    resVecByte(i) := Mux(activeEnS1(i), newVdBytes(i), oldVdBytes(i))
  }

  io.out.bits.res.data := resVecByte.asUInt
}
