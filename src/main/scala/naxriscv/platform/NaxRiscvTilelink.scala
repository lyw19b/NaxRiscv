package naxriscv.platform

import naxriscv.Global.XLEN
import naxriscv.utilities.Plugin
import naxriscv.lsu2._
import naxriscv.fetch._
import naxriscv.misc._
import naxriscv._
import naxriscv.execute.CsrAccessPlugin
import naxriscv.frontend.DecoderPlugin
import naxriscv.lsu.DataCachePlugin
import net.fornwall.jelf.{ElfFile, ElfSection, ElfSectionHeader}
import spinal.core
import spinal.core._
import spinal.core.fiber._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.coherent.{Hub, HubFabric}
import spinal.lib.bus.tilelink.sim.{Checker, Endpoint, MemoryAgent, Monitor, MonitorSubscriber, SlaveDriver, TransactionA, TransactionC, TransactionD}
import spinal.lib.bus.tilelink.{M2sSupport, M2sTransfers, Opcode, S2mSupport, SizeRange, fabric}
import spinal.lib.cpu.riscv.RiscvHart
import spinal.lib.misc.{Elf, TilelinkFabricClint}
import spinal.lib.sim.SparseMemory
import spinal.sim.{Signal, SimManagerContext}

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer



class NaxRiscvTilelink(hartId : Int) extends Area with RiscvHart{
  val ibus = Node.master()
  val dbus = Node.master()
  val pbus = Node.master()

  val buses = List(ibus, dbus, pbus)


  def privPlugin = thread.core.framework.getService[PrivilegedPlugin]
  override def getHartId() = privPlugin.p.hartId
  override def getIntMachineTimer() = privPlugin.io.int.machine.timer
  override def getIntMachineSoftware() = privPlugin.io.int.machine.software
  override def getIntMachineExternal() = privPlugin.io.int.machine.external
  override def getIntSupervisorExternal() = privPlugin.io.int.supervisor.external


//  core.plugins.foreach{
//    case p : FetchCachePlugin => ibus.bus << p.mem.toTilelink()
//    case p : DataCachePlugin =>  dbus.bus << p.mem.toTilelink()
//    case p : Lsu2Plugin => pbus.bus << p.peripheralBus.toTilelink()
//    case p : PrivilegedPlugin => {
//      p.io.int.machine.timer := False
//      p.io.int.machine.software := False

  val thread = Fiber build new Area{
    val l = Config.plugins(
      withCoherency = true,
      withRdTime = false,
      aluCount    = 1,
      decodeCount = 1,
      ioRange = a => a(31 downto 28) === 0x1,
      hartId = hartId
    )

    // Add a plugin to Nax which will handle the negotiation of the tilelink parameters
    l += new Plugin {
      val setup = create early new Area{
        framework.plugins.foreach{
          case p : FetchCachePlugin => ibus.m2s.forceParameters(p.getBusParameter().toTileLinkM2sParameters())
          case p : DataCachePlugin => dbus.m2s.forceParameters(p.setup.dataCacheParameters.memParameter.toTileLinkM2sParameters())
          case p : Lsu2Plugin => pbus.m2s.forceParameters(p.getPeripheralBusParameters().toTileLinkM2sParameters())
          case _ =>
        }

        framework.plugins.foreach{
          case p : FetchCachePlugin => ibus.s2m.supported.load(S2mSupport.none())
          case p : Lsu2Plugin => pbus.s2m.supported.load(S2mSupport.none())
          case p: DataCachePlugin => p.withCoherency match {
            case false => dbus.s2m.supported.load(S2mSupport.none())
            case true => dbus.s2m.supported.load(p.setup.dataCacheParameters.toTilelinkS2mSupported(dbus.s2m.proposed))
          }
          case _ =>
        }

        framework.plugins.foreach{
          case p: DataCachePlugin =>
            if(p.withCoherency)p.setCoherencyInfo(dbus.m2s.parameters.sourceWidth, dbus.s2m.parameters.sinkWidth)
          case _ =>
        }
      }
    }


    val core = new NaxRiscv(l)
    core.plugins.foreach{
      case p : FetchCachePlugin => ibus.bus << p.mem.toTilelink()
      case p : DataCachePlugin =>  dbus.bus << p.mem.toTilelink()
      case p : Lsu2Plugin => pbus.bus << p.peripheralBus.toTilelink()
      case p : PrivilegedPlugin =>
      case _ =>
    }
  }
}



class NaxRiscvTilelinkSoCDemo(cpuCount : Int) extends Component {

  val naxes = for(hartId <- 0 until cpuCount) yield new NaxRiscvTilelink(hartId)

  val filter = new fabric.TransferFilter()
  for(nax <- naxes) filter.up << nax.buses


  val nonCoherent = Node()

//  val hub = new HubFabric()
//  hub.up << filter.down
//  nonCoherent << hub.down

  nonCoherent << filter.down

  val mem = new tilelink.fabric.SlaveBusAny()
  mem.node at SizeMapping(0x80000000l, 0x80000000l) of nonCoherent

  val peripheral = new Area {
    val bus = Node()
    bus at 0x10000000l of nonCoherent

    val clint = new TilelinkFabricClint()
    clint.node at 0x10000 of bus

    for(nax <- naxes) clint.bindHart(nax)


    val emulated = new tilelink.fabric.SlaveBus(
      M2sSupport(
        addressWidth = 28,
        dataWidth = 32,
        transfers = M2sTransfers(
          get = SizeRange(4),
          putFull = SizeRange(4)
        )
      )
    )
    emulated.node << bus

    val custom = Fiber build new Area{
      val mei,sei = in Bool()
      clint.harts.foreach{hart =>
        hart.getIntMachineExternal() := mei
        hart.getIntSupervisorExternal() := sei
      }
    }
  }
}

object NaxRiscvTilelinkGen extends App{
  SpinalVerilog(new NaxRiscvTilelinkSoCDemo(2))
}


/*
NaxRiscv lockstep :
- Global memory
  - Coherent masters update the global memory ordering (SQ, post commit)
  - A history of global memory updates is kept
    That way, the global memory from a little while ago can be reconstructed

- d$
  - Keep a list of committed stores not yet in the global memory ordering (still in the SQ)
  - Update global memory content when a store exit SQ
  - ref load value
    - Each CPU load instruction has to remember the global memory generation it was in, so


- i$
  - The simulation keep track of i$ lines content and the history of line refills
  - Each chunk of the instruction know the i$ line refill generation it came from
  - That ID can then be used to retrieve the expected cache content at the moment of the fetch
  - The simulation model of the i$ content is checked against the global ordering using debugId



Memory ordering tool
- Has its own memory storage
- A history of the content before writes is kept
- Can create multiple "View" allowing to model a CPU perspective
  - Has a list of inflight writes which only apply to the view.
  - Those inflight write will be written in the memory when removed

On CPU store
- On software SQ commit -> Add write to the inflight view
- On hardware SQ exit -> remove write from the inflight view, update memory storage with it

On CPU load
- On hardware D$ access -> Capture memory ordering history ID + inflight stores
- On software access -> provide history ID and overlay inflight stores that completed


CPU probes :
- Commit event
  - PC, RF writes, CSR write
- SQ entry completion
- IO bus read/writes


*/

class RobCtx{
  var pc = -1l;
  var integerWriteValid = false
  var integerWriteData = -1l
  var floatWriteValid = false
  var floatWriteData = -1l
  var floatFlags = -1

  var csrValid = false
  var csrWriteDone = false
  var csrReadDone = false
  var csrAddress = -1
  var csrWriteData = -1l
  var csrReadData = -1l

  var lsuAddress = -1l
  var lsuLen = 0
  var storeValid = false
  var storeData = -1l
  var storeSqId = -1
  var loadValid = false
  var loadLqId = 0
  var loadData = -1l

  var isSc = false
  var scFailure = false

  def clear(){
    integerWriteValid = false;
    floatWriteValid = false;
    csrValid = false;
    csrWriteDone = false;
    csrReadDone = false;
    floatFlags = 0;
    loadValid = false
    storeValid = false
    isSc = false
  }
  clear()
};

import spinal.core.sim._
class NaxSimProbe(nax : NaxRiscv, hartId : Int){
  val alignerPlugin = nax.framework.getService[AlignerPlugin]
  val csrAccessPlugin = nax.framework.getService[CsrAccessPlugin]
  val decodePlugin = nax.framework.getService[DecoderPlugin]
  val commitPlugin = nax.framework.getService[CommitPlugin]
  val privPlugin = nax.framework.getService[PrivilegedPlugin]
  val robPlugin = nax.framework.getService[RobPlugin]
  val lsuPlugin = nax.framework.getService[Lsu2Plugin]
  val intRf = nax.framework.getServiceWhere[RegFilePlugin](_.spec == riscv.IntRegFile)

  val commitWb = commitPlugin.logic.whitebox
  val commitMask = commitWb.commit.mask.simProxy()
  val commitRobId = commitWb.commit.robId.simProxy()
  val commitPc = commitWb.commit_pc.map(_.simProxy()).toArray
  val commitRobToPcValid = commitWb.robToPc.valid.simProxy()
  val commitRobToPcRobId = commitWb.robToPc.robId.simProxy()
  val commitRobToPcValue = commitWb.robToPc.pc.map(_.simProxy()).toArray

  val intRfEvents = intRf.logic.writeEvents.toArray
  val intRfEventsValid = intRfEvents.map(_.valid.simProxy())

  val ioBus = lsuPlugin.peripheralBus
  val ioBusCmdValid = ioBus.cmd.valid.simProxy()
  val ioBusCmdReady = ioBus.cmd.ready.simProxy()
  val ioBusRspValid = ioBus.rsp.valid.simProxy()

  val lsuWb = lsuPlugin.logic.sharedPip.cacheRsp.whitebox
  val lsuWbValid = lsuWb.valid.simProxy()
  val aguWb = lsuPlugin.logic.sharedPip.feed.agu
  val aguWbValid = aguWb.valid.simProxy()
  val wbWb = lsuPlugin.logic.writeback.rsp.whitebox
  val wbWbValid = wbWb.valid.simProxy()
  val lqFlush = lsuPlugin.logic.lqFlush.simProxy()
  val amoLoadWb = lsuPlugin.logic.special.atomic.loadWhitebox
  val amoLoadValid = amoLoadWb.valid.simProxy()
  val amoStoreWb = lsuPlugin.logic.special.atomic.storeWhitebox
  val amoStoreValid = amoStoreWb.valid.simProxy()

  var cycleSinceLastCommit = 0

  val ioInt = privPlugin.io.int
  class IntChecker(pin : Bool, id : Int){
    val proxy = pin.simProxy()
    var last = false
    def check() : Unit = {
      val value = proxy.toBoolean
      if(value != last){
        backends.foreach(_.setInterrupt(hartId, id, value))
        last = value
      }
    }
  }
  val intMachineTimer = new IntChecker(ioInt.machine.timer, 7)
  val intMachineSoftware = new IntChecker(ioInt.machine.software, 3)
  val intMachineExternal = new IntChecker(ioInt.machine.external, 11)
  val intSupervisorExternal = new IntChecker(ioInt.supervisor.external, 9)

  val csrAccess = csrAccessPlugin.logic.whitebox.csrAccess
  val csrAccessValid = csrAccess.valid.simProxy()

  val trapWb = privPlugin.logic.whitebox
  val trapFire = trapWb.trap.fire.simProxy()

  val backends = ArrayBuffer[TraceBackend]()
  val commitsCallbacks = ArrayBuffer[(Int, Long) => Unit]()
  nax.scopeProperties.restore()
  val xlen = decodePlugin.xlen

  val robArray = Array.fill(robPlugin.robSize)(new RobCtx)


  def add(tracer : TraceBackend) : this.type = {
    backends += tracer
    tracer.newCpuMemoryView(hartId, lsuPlugin.lqSize+1, lsuPlugin.sqSize) //+1 because AMO
    tracer.newCpu(hartId, "RV32IMA", "MSU", 32, hartId)
    tracer.setPc(hartId, 0x80000000)
    this
  }

  def checkRob() : Unit = {
    if(commitRobToPcValid.toBoolean){
      val robId = commitRobToPcRobId.toInt
      for(i <- 0 until commitRobToPcValue.size){
        var pc = commitRobToPcValue(i).toLong;
        if(xlen == 32) pc = (pc << 32) >> 32
        val ctx = robArray(robId + i)
        ctx.clear()
        ctx.pc = pc
      }
    }

    for(i <- 0 until intRfEvents.size){
      if(intRfEventsValid(i).toBoolean){
        val port = intRfEvents(i)
        val robId = port.robId.toInt
        val ctx = robArray(robId)
        ctx.integerWriteValid = true
        var data = port.data.toLong
        if(xlen == 32) data = (data << 32) >> 32
        ctx.integerWriteData = data
      }
    }

    if(csrAccessValid.toBoolean){
      val robId = csrAccess.robId.toInt
      val ctx = robArray(robId)
      ctx.csrValid = true
      ctx.csrAddress = csrAccess.address.toInt
      ctx.csrWriteDone = csrAccess.writeDone.toBoolean
      ctx.csrReadDone = csrAccess.readDone.toBoolean
      ctx.csrWriteData = csrAccess.write.toLong
      ctx.csrReadData = csrAccess.read.toLong
    }
  }

  def checkTrap(): Unit ={
    if(trapFire.toBoolean){
      val code = trapWb.trap.code.toInt
      val interrupt = trapWb.trap.interrupt.toBoolean
      backends.foreach(_.trap(hartId, interrupt, code))
    }
  }

  def checkCommits(): Unit ={
    var mask = commitMask.toInt
    cycleSinceLastCommit += 1
    if(cycleSinceLastCommit == 10000){
      simFailure("Nax didn't commited anything since too long")
    }
    for(i <- 0 until commitPlugin.commitCount){
      if((mask & 1) != 0){
        cycleSinceLastCommit = 0
        val robId = commitRobId.toInt + i
        val robCtx = robArray(robId)
        if(robCtx.loadValid){
          backends.foreach(_.loadCommit(hartId, robCtx.loadLqId))
        }
        if(robCtx.isSc){
          backends.foreach(_.storeConditional(hartId, robCtx.scFailure))
        }
        if(robCtx.storeValid){
          backends.foreach(_.storeCommit(hartId, robCtx.storeSqId, robCtx.lsuAddress, robCtx.lsuLen, robCtx.storeData))
        }
        if(robCtx.integerWriteValid){
          backends.foreach(_.writeRf(hartId, 0, 32, robCtx.integerWriteData))
        }
        if(robCtx.csrValid){
          if(robCtx.csrReadDone) backends.foreach(_.readRf(hartId, 4, robCtx.csrAddress, robCtx.csrReadData))
          if(robCtx.csrWriteDone) backends.foreach(_.writeRf(hartId, 4, robCtx.csrAddress, robCtx.csrWriteData))
        }
        backends.foreach(_.commit(hartId, robCtx.pc))
        commitsCallbacks.foreach(_(hartId, robCtx.pc))
      }
      mask >>= 1
    }
  }



  var ioAccess : TraceIo = null

  def checkIoBus(): Unit ={
    if(ioBusCmdValid.toBoolean && ioBusCmdReady.toBoolean){
      assert(ioAccess == null)
      ioAccess = new TraceIo(
        write   = ioBus.cmd.write.toBoolean,
        address = ioBus.cmd.address.toLong,
        data    = ioBus.cmd.data.toLong,
        mask    = ioBus.cmd.mask.toInt,
        size    = 1 << ioBus.cmd.size.toInt,
        error   = false
      )
    }
    if(ioBusRspValid.toBoolean){
      assert(ioAccess != null)
      ioAccess.error = ioBus.rsp.error.toBoolean
      if(!ioAccess.write) {
        val offset = (ioAccess.address) & (ioBus.p.dataWidth/8-1)
        val mask = (1l << ioAccess.size*8)-1
        ioAccess.data = (ioBus.rsp.data.toLong >> offset*8) & mask
      }
      backends.foreach(_.ioAccess(hartId, ioAccess))
      ioAccess = null
    }
  }

  def checkInterrupts(): Unit ={
    intMachineTimer.check()
    intMachineSoftware.check()
    intMachineExternal.check()
    intSupervisorExternal.check()
  }

  def checkLsu(): Unit ={
    if(lsuWbValid.toBoolean){
      val robId = lsuWb.robId.toInt
      val ctx = robArray(robId)
      ctx.lsuAddress = lsuWb.address.toLong
      ctx.lsuLen = 1 << lsuWb.size.toInt
      if(lsuWb.isLoad.toBoolean) {
        ctx.loadValid = true
        ctx.loadData = lsuWb.readData.toLong
        ctx.loadLqId = lsuWb.lqId.toInt
        backends.foreach(_.loadExecute(hartId, ctx.loadLqId, ctx.lsuAddress, ctx.lsuLen, ctx.loadData))
      } else {
        ctx.storeValid = true
      }
    }
    if(aguWbValid.toBoolean){
      val robId = aguWb.robId.toInt
      val ctx = robArray(robId)
      if(!aguWb.load.toBoolean) {
        ctx.storeSqId = (aguWb.aguId.toInt) % lsuPlugin.sqSize
        ctx.storeData = aguWb.data.toLong
      }
    }
    if(wbWbValid.toBoolean){
      val sqId = wbWb.sqId.toInt
      backends.foreach(_.storeBroadcast(hartId, sqId))
    }
    if(lqFlush.toBoolean){
      backends.foreach(_.loadFlush(hartId))
    }
    if(amoLoadValid.toBoolean){
      val robId = amoLoadWb.robIdV.toInt
      val ctx = robArray(robId)

      ctx.loadValid = true
      ctx.loadData = amoLoadWb.readData.toLong
      ctx.loadLqId = lsuPlugin.lqSize
      backends.foreach(_.loadExecute(hartId, ctx.loadLqId, ctx.lsuAddress, ctx.lsuLen, ctx.loadData))
    }
    if(amoStoreValid.toBoolean){
      val robId = amoStoreWb.robIdV.toInt
      val ctx = robArray(robId)
      ctx.storeValid = true
      ctx.storeData = amoStoreWb.storeData.toLong

      if(amoStoreWb.isSc.toBoolean){
        val passed = amoStoreWb.scPassed.toBoolean
        if(!passed) ctx.storeValid = false
        ctx.isSc = true
        ctx.scFailure = !passed
      }
    }
  }

  nax.clockDomain.onSamplings{
    checkLsu()
    checkCommits()
    checkTrap()
    checkIoBus()
    checkRob()
    checkInterrupts()
  }
}

class TraceIo (var write: Boolean,
               var address: Long,
               var data: Long,
               var mask: Int,
               var size: Int,
               var error: Boolean){
  def serialized() = f"${write.toInt} $address%016x $data%016x $mask%02x $size ${error.toInt}"
}
trait TraceBackend{
  def newCpuMemoryView(viewId : Int, readIds : Long, writeIds : Long)
  def newCpu(hartId : Int, isa : String, priv : String, physWidth : Int, memoryViewId : Int) : Unit
  def loadElf(offset : Long, path : File) : Unit
  def loadBin(offset : Long, path : File) : Unit
  def setPc(hartId : Int, pc : Long): Unit
  def writeRf(hardId : Int, rfKind : Int, address : Int, data : Long) //address out of range mean unknown
  def readRf(hardId : Int, rfKind : Int, address : Int, data : Long) //address out of range mean unknown
  def commit(hartId : Int, pc : Long): Unit
  def trap(hartId: Int, interrupt : Boolean, code : Int)
  def ioAccess(hartId: Int, access : TraceIo) : Unit
  def setInterrupt(hartId : Int, intId : Int, value : Boolean) : Unit

  def loadExecute(hartId: Int, id : Long, addr : Long, len : Long, data : Long) : Unit
  def loadCommit(hartId: Int, id : Long) : Unit
  def loadFlush(hartId: Int) : Unit
  def storeCommit(hartId: Int, id : Long, addr : Long, len : Long, data : Long) : Unit
  def storeBroadcast(hartId: Int, id : Long) : Unit
  def storeConditional(hartId : Int, failure: Boolean) : Unit

  def time(value : Long) : Unit

  def flush() : Unit
  def close() : Unit
}

class DummyBackend() extends TraceBackend{
  override def newCpuMemoryView(viewId: Int, readIds: Long, writeIds: Long) = {}
  override def newCpu(hartId: Int, isa: String, priv: String, physWidth: Int, memoryViewId: Int) = {}
  override def loadElf(offset: Long, path: File) = {}
  override def loadBin(offset: Long, path: File) = {}
  override def setPc(hartId: Int, pc: Long) = {}
  override def writeRf(hardId: Int, rfKind: Int, address: Int, data: Long) = {}
  override def readRf(hardId: Int, rfKind: Int, address: Int, data: Long) = {}
  override def commit(hartId: Int, pc: Long) = {}
  override def trap(hartId: Int, interrupt: Boolean, code: Int) = {}
  override def ioAccess(hartId: Int, access: TraceIo) = {}
  override def setInterrupt(hartId: Int, intId: Int, value: Boolean) = {}
  override def loadExecute(hartId: Int, id: Long, addr: Long, len: Long, data: Long) = {}
  override def loadCommit(hartId: Int, id: Long) = {}
  override def loadFlush(hartId: Int) = {}
  override def storeCommit(hartId: Int, id: Long, addr: Long, len: Long, data: Long) = {}
  override def storeBroadcast(hartId: Int, id: Long) = {}
  override def storeConditional(hartId: Int, failure: Boolean) = {}
  override def time(value: Long) = {}
  override def flush() = {}
  override def close() = {}
}

class FileBackend(f : File) extends TraceBackend{
  val bf = new BufferedWriter(new FileWriter(f))

  override def commit(hartId: Int, pc: Long) = {
    bf.write(f"rv commit $hartId $pc%016x\n")
  }

  override def trap(hartId: Int, interrupt : Boolean, code : Int): Unit ={
    bf.write(f"rv trap $hartId ${interrupt.toInt} $code\n")
  }

  override def writeRf(hartId: Int, rfKind: Int, address: Int, data: Long) = {
    bf.write(f"rv rf w $hartId $rfKind $address $data%016x\n")
  }

  override def readRf(hartId: Int, rfKind: Int, address: Int, data: Long) = {
    bf.write(f"rv rf r $hartId $rfKind $address $data%016x\n")
  }

  def ioAccess(hartId: Int, access : TraceIo) : Unit = {
    bf.write(f"rv io $hartId ${access.serialized()}\n")
  }

  override def setInterrupt(hartId: Int, intId: Int, value: Boolean) = {
    bf.write(f"rv int set $hartId $intId ${value.toInt}\n")
  }

  override def loadElf(offset: Long, path: File) = {
    bf.write(f"elf load  $offset%016x ${path.getAbsolutePath}\n")
  }

  override def loadBin(offset: Long, path: File) = {
    bf.write(f"bin load $offset%016x ${path.getAbsolutePath} \n")
  }
  override def setPc(hartId: Int, pc: Long) = {
    bf.write(f"rv set pc $hartId $pc%016x\n")
  }

  override def newCpuMemoryView(memoryViewId : Int, readIds : Long, writeIds : Long) = {
    bf.write(f"memview new $memoryViewId $readIds $writeIds\n")
  }

  override def newCpu(hartId: Int, isa : String, priv : String, physWidth : Int, memoryViewId : Int) = {
    bf.write(f"rv new $hartId $isa $priv $physWidth $memoryViewId\n")
  }

  override def loadExecute(hartId: Int, id : Long, addr : Long, len : Long, data : Long) : Unit = {
    bf.write(f"rv load exe $hartId $id $len $addr%016x $data%016x\n")
  }
  override def loadCommit(hartId: Int, id : Long) : Unit = {
    bf.write(f"rv load com $hartId $id\n")
  }
  override def loadFlush(hartId: Int) : Unit = {
    bf.write(f"rv load flu $hartId\n")
  }
  override def storeCommit(hartId: Int, id : Long, addr : Long, len : Long, data : Long) : Unit = {
    bf.write(f"rv store com $hartId $id $len $addr%016x $data%016x\n")
  }
  override def storeBroadcast(hartId: Int, id : Long) : Unit = {
    bf.write(f"rv store bro $hartId $id\n")
  }
  override def storeConditional(hartId: Int, failure: Boolean) = {
    bf.write(f"rv store sc $hartId ${failure.toInt}\n")
  }

  override def time(value: Long) = bf.write(f"time $value\n")


  override def flush() = bf.flush()
  override def close() = bf.close()

  def spinalSimFlusher(period : Long): Unit ={
    periodicaly(period)(flush())
    onSimEnd(close())
  }
  def spinalSimTime(period : Long): Unit ={
    periodicaly(period)(time(simTime()))
  }
}

object NaxRiscvTilelinkSim extends App{
  val sc = SimConfig
  sc.allOptimisation
  sc.withFstWave
  sc.withConfig(SpinalConfig().includeSimulation)
  sc.addSimulatorFlag("--threads 1")

  val compiled = sc.compile(new NaxRiscvTilelinkSoCDemo(1))

  def doIt() {
    compiled.doSimUntilVoid(seed = 42) { dut =>
      fork {
        disableSimWave()
        while(true) {
          disableSimWave()
          sleep(100000 * 10)
          enableSimWave()
          sleep(  100 * 10)
        }
//        waitUntil(simTime() > 15323180-100000)
//        enableSimWave()
//        sleep(500000)
//        disableSimWave()
//        simFailure("done")
//
//        waitUntil(simTime() > 128815290-300000)
//        enableSimWave()
//        sleep(400000)
//        disableSimWave()
//
//        simFailure("done")
      }



      val cd = dut.clockDomain
      cd.forkStimulus(10)
//      cd.forkSimSpeedPrinter(4.0)



      val memAgent = new MemoryAgent(dut.mem.node.bus, cd, seed = 0, randomProberFactor = 0.2f)(null){
//        override def onA(a: TransactionA) = {
//          if(a.address == 0x0FE5C7C0l){
//            println(f"\nGOT IT AT ${a.opcode} ${a.param} $simTime\n")
//          }
//          super.onA(a)
//        }
//        override def onC(c: TransactionC) = {
//          if(c.address == 0x0FE5C7C0l){
//            println(f"\nGOT IT AT ${c.opcode} ${c.param} $simTime\n")
//          }
//          super.onC(c)
//        }
//        override def onD(d: TransactionD) = {
//          if(d.address == 0x0FE5C7C0l){
//            println(f"\nGOT IT AT ${d.opcode} ${d.param} $simTime\n")
//          }
//          super.onD(d)
//        }
      }
      memAgent.mem.randOffset = 0x80000000l
      val checker = Checker(memAgent.monitor)

      val peripheralAgent = new PeripheralEmulator(dut.peripheral.emulated.node.bus, dut.peripheral.custom.mei, dut.peripheral.custom.sei, cd)

//      val tracer = new FileBackend(new File("trace.log"))
//      tracer.spinalSimFlusher(10*10000)
//      tracer.spinalSimTime(10000)
//
//      val naxes = dut.naxes.map(nax =>
//        new NaxSimProbe(nax.thread.core, nax.getHartId()).add(tracer)
//      )

////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/dhrystone/build/rv32ima/dhrystone.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/coremark/build/rv32ima/coremark.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/freertosDemo/build/rv32ima/freertosDemo.elf"))
//      val elf = new Elf(new File("ext/NaxSoftware/baremetal/play/build/rv32ima/play.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/coherency/build/rv32ima/coherency.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/machine/build/rv32ima/machine.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/supervisor/build/rv32ima/supervisor.elf"))
////      val elf = new Elf(new File("ext/NaxSoftware/baremetal/mmu_sv32/build/rv32ima/mmu_sv32.elf"))
//
//      elf.load(memAgent.mem, -0xffffffff80000000l)
//      tracer.loadElf(0, elf.f)
//
//
//      val passSymbol = elf.getSymbolAddress("pass")
//      val failSymbol = elf.getSymbolAddress("fail")
//      naxes.foreach { nax =>
//        nax.commitsCallbacks += { (hartId, pc) =>
//          if (pc == passSymbol) delayed(1)(simSuccess())
//          if (pc == failSymbol) delayed(1)(simFailure("Software reach the fail symbole :("))
//        }
//      }

      memAgent.mem.loadBin(0x00000000l, "ext/NaxSoftware/buildroot/images/rv32ima/fw_jump.bin")
      memAgent.mem.loadBin(0x00F80000l, "ext/NaxSoftware/buildroot/images/rv32ima/linux.dtb")
      memAgent.mem.loadBin(0x00400000l, "ext/NaxSoftware/buildroot/images/rv32ima/Image")
      memAgent.mem.loadBin(0x01000000l, "ext/NaxSoftware/buildroot/images/rv32ima/rootfs.cpio")

//      tracer.loadBin(0x80000000l, new File("ext/NaxSoftware/buildroot/images/rv32ima/fw_jump.bin"))
//      tracer.loadBin(0x80F80000l, new File("ext/NaxSoftware/buildroot/images/rv32ima/linux.dtb"))
//      tracer.loadBin(0x80400000l, new File("ext/NaxSoftware/buildroot/images/rv32ima/Image"))
//      tracer.loadBin(0x81000000l, new File("ext/NaxSoftware/buildroot/images/rv32ima/rootfs.cpio"))



//      cd.waitSampling(4000000)
//      simSuccess()
    }
  }

  for(i <- 0 until 1) {
    new Thread {
      override def run {
        doIt()
      }
    }.start()
  }
}
