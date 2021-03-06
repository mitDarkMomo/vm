package one.inve.contract.inve.vm;

import one.inve.contract.ethplugin.config.BlockchainConfig;
import one.inve.contract.ethplugin.vm.*;
import one.inve.contract.inve.vm.hook.VMHook;
import one.inve.contract.inve.vm.program.INVEProgram;
import one.inve.contract.inve.vm.program.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static one.inve.contract.ethplugin.crypto.HashUtil.sha3;
import static one.inve.contract.ethplugin.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static one.inve.contract.ethplugin.util.ByteUtil.toHexString;
import static one.inve.contract.ethplugin.vm.OpCode.*;
import static one.inve.contract.ethplugin.vm.VMUtils.getSizeInWords;

public class VM {

    private static final Logger logger = LoggerFactory.getLogger("VM");
    // private static final Logger dumpLogger = LoggerFactory.getLogger("dump");
    private static BigInteger _32_ = BigInteger.valueOf(32);
    private static final String logString = "{}    Op: [{}]  Gas: [{}] Deep: [{}]  Hint: [{}]";

    // max mem size which couldn't be paid for ever
    // used to reduce expensive BigInt arithmetic
    private static BigInteger MAX_MEM_SIZE = BigInteger.valueOf(Integer.MAX_VALUE);

    /* Keeps track of the number of steps performed in this VM */
    // private int vmCounter = 0;

    // private long dumpBlock;

    private static final Map<OpCode, Boolean> opValidators = new HashMap<OpCode, Boolean>()
    {private static final long serialVersionUID = 1L;

	{
        put(DELEGATECALL, true);
        put(REVERT, true);
        put(RETURNDATACOPY, true);
        put(RETURNDATASIZE, true);
        put(STATICCALL, true);
        put(EXTCODEHASH, true);
        put(SHL, true);
        put(SHR, true);
        put(SAR, true);
        put(CREATE2, false);
    }};

    // deprecated field that holds VM hook. Will be removed in the future releases.
    private static VMHook deprecatedHook = VMHook.EMPTY;
    private final boolean hasHooks;
    private final VMHook[] hooks;

    public VM() {
        this(VMHook.EMPTY);
    }

    @Autowired
    public VM(VMHook hook) {
        // this.dumpBlock = config.dumpBlock();
        this.hooks = Stream.of(deprecatedHook, hook)
                .filter(h -> !h.isEmpty())
                .toArray(VMHook[]::new);
        this.hasHooks = this.hooks.length > 0;
    }

    private void onHookEvent(Consumer<VMHook> consumer) {
        for (VMHook hook : this.hooks) {
            consumer.accept(hook);
        }
    }

    private long calcMemGas(GasCost gasCosts, long oldMemSize, BigInteger newMemSize, long copySize) {
        long gasCost = 0;

        // Avoid overflows
        if (newMemSize.compareTo(MAX_MEM_SIZE) > 0) {
            throw INVEProgram.Exception.gasOverflow(newMemSize, MAX_MEM_SIZE);
        }

        // memory gas calc
        long memoryUsage = (newMemSize.longValue() + 31) / 32 * 32;
        if (memoryUsage > oldMemSize) {
            long memWords = (memoryUsage / 32);
            long memWordsOld = (oldMemSize / 32);
            //TODO #POC9 c_quadCoeffDiv = 512, this should be a constant, not magic number
            long memGas = (gasCosts.getMEMORY() * memWords + memWords * memWords / 512)
                    - (gasCosts.getMEMORY() * memWordsOld + memWordsOld * memWordsOld / 512);
            gasCost += memGas;
        }

        if (copySize > 0) {
            long copyGas = gasCosts.getCOPY_GAS() * ((copySize + 31) / 32);
            gasCost += copyGas;
        }
        return gasCost;
    }

    private boolean isDeadAccount(INVEProgram program, byte[] addr) {
        return !program.getStorage().isExist(addr) || program.getStorage().getAccountState(addr).isEmpty();
    }

    /**
     * Validates whether operation is allowed
     * with current blockchain config
     * @param op        VM operation
     * @param program   Current program
     */
    private void validateOp(OpCode op, INVEProgram program) {
        if (!(opValidators.containsKey(op))) return;

        if (!opValidators.get(op)) {
            throw INVEProgram.Exception.invalidOpCode(program.getCurrentOp());
        }
    }

    public void step(INVEProgram program) {
        program.saveOpTrace();

        try {
            BlockchainConfig blockchainConfig = program.getBlockchainConfig();

            OpCode op = OpCode.code(program.getCurrentOp());
            if (op == null) {
                throw INVEProgram.Exception.invalidOpCode(program.getCurrentOp());
            }

            validateOp(op, program);

            program.setLastOp(op.val());
            program.verifyStackSize(op.require());
            program.verifyStackOverflow(op.require(), op.ret()); //Check not exceeding stack limits

            long oldMemSize = program.getMemSize();
            Stack stack = program.getStack();

            String hint = "";
            // long callGas = 0, memWords = 0; // parameters for logging
            long gasCost = op.getTier().asInt();
            // long gasBefore = program.getGasLong();
            // int stepBefore = program.getPC();
            GasCost gasCosts = blockchainConfig.getGasCost();
            DataWord adjustedCallGas = null;

            /*DEBUG #POC9 if( op.asInt() == 96 || op.asInt() == -128 || op.asInt() == 57 || op.asInt() == 115) {
              //byte alphaone = 0x63;
              //op = OpCode.code(alphaone);
              gasCost = 3;
            }

            if( op.asInt() == -13 ) {
              //byte alphaone = 0x63;
              //op = OpCode.code(alphaone);
              gasCost = 0;
            }*/

            // Calculate fees and spend gas
            switch (op) {
                case STOP:
                    gasCost = gasCosts.getSTOP();
                    break;
                case SUICIDE:
                    gasCost = gasCosts.getSUICIDE();
                    DataWord suicideAddressWord = stack.get(stack.size() - 1);
                    if (blockchainConfig.eip161()) {
                        if (isDeadAccount(program, suicideAddressWord.getLast20Bytes()) &&
                                !program.getBalance(program.getOwnerAddress()).isZero()) {
                            gasCost += gasCosts.getNEW_ACCT_SUICIDE();
                        }
                    } else {
                        if (!program.getStorage().isExist(suicideAddressWord.getLast20Bytes())) {
                            gasCost += gasCosts.getNEW_ACCT_SUICIDE();
                        }
                    }
                    break;
                case SSTORE:
                    DataWord currentValue = program.getCurrentValue(stack.peek());
                    if (currentValue == null) currentValue = DataWord.ZERO;
                    DataWord newValue = stack.get(stack.size() - 2);

                    if (blockchainConfig.eip1283()) { // Net gas metering for SSTORE
                        if (newValue.equals(currentValue)) {
                            gasCost = gasCosts.getREUSE_SSTORE();
                        } else {
                            DataWord origValue = program.getOriginalValue(stack.peek());
                            if (origValue == null) origValue = DataWord.ZERO;
                            if (currentValue.equals(origValue)) {
                                if (origValue.isZero()) {
                                    gasCost = gasCosts.getSET_SSTORE();
                                } else {
                                    gasCost = gasCosts.getCLEAR_SSTORE();
                                    if (newValue.isZero()) {
                                        program.futureRefundGas(gasCosts.getREFUND_SSTORE());
                                    }
                                }
                            } else {
                                gasCost = gasCosts.getREUSE_SSTORE();
                                if (!origValue.isZero()) {
                                    if (currentValue.isZero()) {
                                        program.futureRefundGas(-gasCosts.getREFUND_SSTORE());
                                    } else if (newValue.isZero()) {
                                        program.futureRefundGas(gasCosts.getREFUND_SSTORE());
                                    }
                                }
                                if (origValue.equals(newValue)) {
                                    if (origValue.isZero()) {
                                        program.futureRefundGas(gasCosts.getSET_SSTORE() - gasCosts.getREUSE_SSTORE());
                                    } else {
                                        program.futureRefundGas(gasCosts.getCLEAR_SSTORE() - gasCosts.getREUSE_SSTORE());
                                    }
                                }
                            }
                        }
                    } else { // Before EIP-1283 cost calculation
                        if (currentValue.isZero() && !newValue.isZero())
                            gasCost = gasCosts.getSET_SSTORE();
                        else if (!currentValue.isZero() && newValue.isZero()) {
                            // refund step cost policy.
                            program.futureRefundGas(gasCosts.getREFUND_SSTORE());
                            gasCost = gasCosts.getCLEAR_SSTORE();
                        } else {
                            gasCost = gasCosts.getRESET_SSTORE();
                        }
                    }
                    break;
                case SLOAD:
                    gasCost = gasCosts.getSLOAD();
                    break;
                case BALANCE:
                    gasCost = gasCosts.getBALANCE();
                    break;

                // These all operate on memory and therefore potentially expand it:
                case MSTORE:
                    gasCost += calcMemGas(gasCosts, oldMemSize, memNeeded(stack.peek(), DataWord.of(32)), 0);
                    break;
                case MSTORE8:
                    gasCost += calcMemGas(gasCosts, oldMemSize, memNeeded(stack.peek(), DataWord.ONE), 0);
                    break;
                case MLOAD:
                    gasCost += calcMemGas(gasCosts, oldMemSize, memNeeded(stack.peek(), DataWord.of(32)), 0);
                    break;
                case RETURN:
                case REVERT:
                    gasCost = gasCosts.getSTOP() + calcMemGas(gasCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    break;
                case SHA3:
                    gasCost = gasCosts.getSHA3() + calcMemGas(gasCosts, oldMemSize, memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    DataWord size = stack.get(stack.size() - 2);
                    long chunkUsed = getSizeInWords(size.longValueSafe());
                    gasCost += chunkUsed * gasCosts.getSHA3_WORD();
                    break;
                case CALLDATACOPY:
                case RETURNDATACOPY:
                    gasCost += calcMemGas(gasCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 3)),
                            stack.get(stack.size() - 3).longValueSafe());
                    break;
                case CODECOPY:
                    gasCost += calcMemGas(gasCosts, oldMemSize,
                            memNeeded(stack.peek(), stack.get(stack.size() - 3)),
                            stack.get(stack.size() - 3).longValueSafe());
                    break;
                case EXTCODESIZE:
                    gasCost = gasCosts.getEXT_CODE_SIZE();
                    break;
                case EXTCODECOPY:
                    gasCost = gasCosts.getEXT_CODE_COPY() + calcMemGas(gasCosts, oldMemSize,
                            memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 4)),
                            stack.get(stack.size() - 4).longValueSafe());
                    break;
                case EXTCODEHASH:
                    gasCost = gasCosts.getEXT_CODE_HASH();
                    break;
                case CALL:
                case CALLCODE:
                case DELEGATECALL:
                case STATICCALL:

                    gasCost = gasCosts.getCALL();
                    DataWord callGasWord = stack.get(stack.size() - 1);

                    DataWord callAddressWord = stack.get(stack.size() - 2);

                    DataWord value = op.callHasValue() ?
                            stack.get(stack.size() - 3) : DataWord.ZERO;

                    //check to see if account does not exist and is not a precompiled contract
                    if (op == CALL) {
                        if (blockchainConfig.eip161()) {
                            if (isDeadAccount(program, callAddressWord.getLast20Bytes()) && !value.isZero()) {
                                gasCost += gasCosts.getNEW_ACCT_CALL();
                            }
                        } else {
                            if (!program.getStorage().isExist(callAddressWord.getLast20Bytes())) {
                                gasCost += gasCosts.getNEW_ACCT_CALL();
                            }
                        }
                    }

                    //TODO #POC9 Make sure this is converted to BigInteger (256num support)
                    if (!value.isZero() )
                        gasCost += gasCosts.getVT_CALL();

                    int opOff = op.callHasValue() ? 4 : 3;
                    BigInteger in = memNeeded(stack.get(stack.size() - opOff), stack.get(stack.size() - opOff - 1)); // in offset+size
                    BigInteger out = memNeeded(stack.get(stack.size() - opOff - 2), stack.get(stack.size() - opOff - 3)); // out offset+size
                    gasCost += calcMemGas(gasCosts, oldMemSize, in.max(out), 0);

                    if (gasCost > program.getGas().longValueSafe()) {
                        throw INVEProgram.Exception.notEnoughOpGas(op, callGasWord, program.getGas());
                    }

                    DataWord gasLeft = program.getGas();
                    DataWord subResult = gasLeft.sub(DataWord.of(gasCost));
                    adjustedCallGas = blockchainConfig.getCallGas(op, callGasWord, subResult);
                    gasCost += adjustedCallGas.longValueSafe();
                    break;
                case CREATE:
                    gasCost = gasCosts.getCREATE() + calcMemGas(gasCosts, oldMemSize,
                            memNeeded(stack.get(stack.size() - 2), stack.get(stack.size() - 3)), 0);
                    break;
                case CREATE2:
                    DataWord codeSize = stack.get(stack.size() - 3);
                    gasCost = gasCosts.getCREATE() +
                            calcMemGas(gasCosts, oldMemSize, memNeeded(stack.get(stack.size() - 2), codeSize), 0) +
                            getSizeInWords(codeSize.longValueSafe()) * gasCosts.getSHA3_WORD();
                    break;
                case LOG0:
                case LOG1:
                case LOG2:
                case LOG3:
                case LOG4:

                    int nTopics = op.val() - OpCode.LOG0.val();

                    BigInteger dataSize = stack.get(stack.size() - 2).value();
                    BigInteger dataCost = dataSize.multiply(BigInteger.valueOf(gasCosts.getLOG_DATA_GAS()));
                    if (program.getGas().value().compareTo(dataCost) < 0) {
                        throw INVEProgram.Exception.notEnoughOpGas(op, dataCost, program.getGas().value());
                    }

                    gasCost = gasCosts.getLOG_GAS() +
                            gasCosts.getLOG_TOPIC_GAS() * nTopics +
                            gasCosts.getLOG_DATA_GAS() * stack.get(stack.size() - 2).longValue() +
                            calcMemGas(gasCosts, oldMemSize, memNeeded(stack.peek(), stack.get(stack.size() - 2)), 0);
                    break;
                case EXP:

                    DataWord exp = stack.get(stack.size() - 2);
                    int bytesOccupied = exp.bytesOccupied();
                    gasCost = gasCosts.getEXP_GAS() + gasCosts.getEXP_BYTE_GAS() * bytesOccupied;
                    break;
                default:
                    break;
            }

            //DEBUG System.out.println(" OP IS " + op.name() + " GASCOST IS " + gasCost + " NUM IS " + op.asInt());
            logger.info("op name is: {}", op.name());
            program.spendGas(gasCost, op.name());

            // Log debugging line for VM
            // if (program.getNumber().intValue() == dumpBlock) {
            //     this.dumpLine(op, gasBefore, gasCost + callGas, memWords, program);
            // }

            if (hasHooks) {
                onHookEvent(hook -> hook.step(program, op));
            }

            // Execute operation
            switch (op) {
                /**
                 * Stop and Arithmetic Operations
                 */
                case STOP: {
                    program.setHReturn(EMPTY_BYTE_ARRAY);
                    program.stop();
                }
                break;
                case ADD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " + " + word2.value();

                    DataWord addResult = word1.add(word2);
                    program.stackPush(addResult);
                    program.step();

                }
                break;
                case MUL: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " * " + word2.value();

                    DataWord mulResult = word1.mul(word2);
                    program.stackPush(mulResult);
                    program.step();
                }
                break;
                case SUB: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " - " + word2.value();

                    DataWord subResult = word1.sub(word2);
                    program.stackPush(subResult);
                    program.step();
                }
                break;
                case DIV: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " / " + word2.value();

                    DataWord divResult = word1.div(word2);
                    program.stackPush(divResult);
                    program.step();
                }
                break;
                case SDIV: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " / " + word2.sValue();

                    DataWord sDivResult = word1.sDiv(word2);
                    program.stackPush(sDivResult);
                    program.step();
                }
                break;
                case MOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " % " + word2.value();

                    DataWord modResult = word1.mod(word2);
                    program.stackPush(modResult);
                    program.step();
                }
                break;
                case SMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " #% " + word2.sValue();

                    DataWord sModResult = word1.sMod(word2);
                    program.stackPush(sModResult);
                    program.step();
                }
                break;
                case EXP: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " ** " + word2.value();

                    DataWord expResult = word1.exp(word2);
                    program.stackPush(expResult);
                    program.step();
                }
                break;
                case SIGNEXTEND: {
                    DataWord word1 = program.stackPop();
                    BigInteger k = word1.value();

                    if (k.compareTo(_32_) < 0) {
                        DataWord word2 = program.stackPop();
                        if (logger.isInfoEnabled())
                            hint = word1 + "  " + word2.value();
                        DataWord extendResult = word2.signExtend(k.byteValue());
                        program.stackPush(extendResult);
                    }
                    program.step();
                }
                break;
                case NOT: {
                    DataWord word1 = program.stackPop();
                    DataWord bnotWord = word1.bnot();

                    if (logger.isInfoEnabled())
                        hint = "" + bnotWord.value();

                    program.stackPush(bnotWord);
                    program.step();
                }
                break;
                case LT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " < " + word2.value();

                    if (word1.value().compareTo(word2.value()) == -1) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }
                    program.step();
                }
                break;
                case SLT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " < " + word2.sValue();

                    if (word1.sValue().compareTo(word2.sValue()) == -1) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }
                    program.step();
                }
                break;
                case SGT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.sValue() + " > " + word2.sValue();

                    if (word1.sValue().compareTo(word2.sValue()) == 1) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }
                    program.step();
                }
                break;
                case GT: {
                    // TODO: can be improved by not using BigInteger
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " > " + word2.value();

                    if (word1.value().compareTo(word2.value()) == 1) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }
                    program.step();
                }
                break;
                case EQ: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " == " + word2.value();

                    DataWord xorResult = word1.xor(word2);
                    if (xorResult.isZero()) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }
                    program.step();
                }
                break;
                case ISZERO: {
                    DataWord word1 = program.stackPop();
                    if (word1.isZero()) {
                        program.stackPush(DataWord.ONE);
                    } else {
                        program.stackPush(DataWord.ZERO);
                    }

                    if (logger.isInfoEnabled())
                        hint = "" + word1.value();

                    program.step();
                }
                break;

                /**
                 * Bitwise Logic Operations
                 */
                case AND: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    if(word1.toString().equals("000000000000000000000000ffffffffffffffffffffffffffffffffffffffff"))
                    {
                        word1= DataWord.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
                    }
                    if(word2.toString().equals("000000000000000000000000ffffffffffffffffffffffffffffffffffffffff"))
                    {
                        word2= DataWord.of("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
                    }

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " && " + word2.value();

                    DataWord andResult = word1.and(word2);
                    program.stackPush(andResult);
                    program.step();
                }
                break;
                case OR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " || " + word2.value();

                    DataWord orResult = word1.or(word2);
                    program.stackPush(orResult);
                    program.step();
                }
                break;
                case XOR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = word1.value() + " ^ " + word2.value();

                    DataWord xorResult = word1.xor(word2);
                    program.stackPush(xorResult);
                    program.step();
                }
                break;
                case BYTE: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    final DataWord result;
                    if (word1.value().compareTo(_32_) == -1) {
                        byte tmp = word2.getData()[word1.intValue()];
                        result = DataWord.of(tmp);
                    } else {
                        result = DataWord.ZERO;
                    }

                    if (logger.isInfoEnabled())
                        hint = "" + result.value();

                    program.stackPush(result);
                    program.step();
                }
                break;
                case SHL: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    final DataWord result = word2.shiftLeft(word1);

                    if (logger.isInfoEnabled())
                        hint = "" + result.value();

                    program.stackPush(result);
                    program.step();
                }
                break;
                case SHR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    final DataWord result = word2.shiftRight(word1);

                    if (logger.isInfoEnabled())
                        hint = "" + result.value();

                    program.stackPush(result);
                    program.step();
                }
                break;
                case SAR: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    final DataWord result = word2.shiftRightSigned(word1);

                    if (logger.isInfoEnabled())
                        hint = "" + result.value();

                    program.stackPush(result);
                    program.step();
                }
                break;
                case ADDMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    DataWord word3 = program.stackPop();
                    DataWord addmodResult = word1.addmod(word2, word3);
                    program.stackPush(addmodResult);
                    program.step();
                }
                break;
                case MULMOD: {
                    DataWord word1 = program.stackPop();
                    DataWord word2 = program.stackPop();
                    DataWord word3 = program.stackPop();
                    DataWord mulmodResult = word1.mulmod(word2, word3);
                    program.stackPush(mulmodResult);
                    program.step();
                }
                break;

                /**
                 * SHA3
                 */
                case SHA3: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();
                    byte[] buffer = program.memoryChunk(memOffsetData.intValueSafe(), lengthData.intValueSafe());

                    byte[] encoded = sha3(buffer);
                    DataWord word = DataWord.of(encoded);

                    if (logger.isInfoEnabled())
                        hint = word.toString();

                    program.stackPush(word);
                    program.step();
                }
                break;

                /**
                 * Environmental Information
                 */
                case ADDRESS: {
                    DataWord address = program.getOwnerAddress();

                    if (logger.isInfoEnabled())
                        hint = "address: " + toHexString(address.getLast20Bytes());

                    program.stackPush(address);
                    program.step();
                }
                break;
                case BALANCE: {
                    DataWord address = program.stackPop();
                    DataWord balance = program.getBalance(address);

                    if (logger.isInfoEnabled())
                        hint = "address: "
                                + toHexString(address.getLast20Bytes())
                                + " balance: " + balance.toString();

                    program.stackPush(balance);
                    program.step();
                }
                break;
                case ORIGIN: {
                    DataWord originAddress = program.getOriginAddress();

                    if (logger.isInfoEnabled())
                        hint = "address: " + toHexString(originAddress.getLast20Bytes());

                    program.stackPush(originAddress);
                    program.step();
                }
                break;
                case CALLER: {
                    DataWord callerAddress = program.getCallerAddress();

                    if (logger.isInfoEnabled())
                        hint = "address: " + toHexString(callerAddress.getLast20Bytes());

                    program.stackPush(callerAddress);
                    program.step();
                }
                break;
                case CALLVALUE: {
                    DataWord callValue = program.getCallValue();

                    if (logger.isInfoEnabled())
                        hint = "value: " + callValue;

                    program.stackPush(callValue);
                    program.step();
                }
                break;
                case CALLDATALOAD: {
                    DataWord dataOffs = program.stackPop();
                    DataWord value = program.getDataValue(dataOffs);

                    if (logger.isInfoEnabled())
                        hint = "data: " + value;

                    program.stackPush(value);
                    program.step();
                }
                break;
                case CALLDATASIZE: {
                    DataWord dataSize = program.getDataSize();

                    if (logger.isInfoEnabled())
                        hint = "size: " + dataSize.value();

                    program.stackPush(dataSize);
                    program.step();
                }
                break;
                case CALLDATACOPY: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord dataOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();

                    byte[] msgData = program.getDataCopy(dataOffsetData, lengthData);

                    if (logger.isInfoEnabled())
                        hint = "data: " + toHexString(msgData);

                    program.memorySave(memOffsetData.intValueSafe(), lengthData.intValueSafe(), msgData);
                    program.step();
                }
                break;
                case RETURNDATASIZE: {
                    DataWord dataSize = program.getReturnDataBufferSize();

                    if (logger.isInfoEnabled())
                        hint = "size: " + dataSize.value();

                    program.stackPush(dataSize);
                    program.step();
                }
                break;
                case RETURNDATACOPY: {
                    DataWord memOffsetData = program.stackPop();
                    DataWord dataOffsetData = program.stackPop();
                    DataWord lengthData = program.stackPop();

                    byte[] msgData = program.getReturnDataBufferData(dataOffsetData, lengthData);

                    if (msgData == null) {
                        throw new INVEProgram.ReturnDataCopyIllegalBoundsException(dataOffsetData, lengthData, program.getReturnDataBufferSize().longValueSafe());
                    }

                    if (logger.isInfoEnabled())
                        hint = "data: " + toHexString(msgData);

                    program.memorySave(memOffsetData.intValueSafe(), lengthData.intValueSafe(), msgData);
                    program.step();
                }
                break;
                case CODESIZE:
                case EXTCODESIZE: {

                    int length;
                    if (op == OpCode.CODESIZE)
                        length = program.getCode().length;
                    else {
                        DataWord address = program.stackPop();
                        length = program.getCodeAt(address).length;
                    }
                    DataWord codeLength = DataWord.of(length);

                    if (logger.isInfoEnabled())
                        hint = "size: " + length;

                    program.stackPush(codeLength);
                    program.step();
                }
                break;
                case CODECOPY:
                case EXTCODECOPY: {

                    byte[] fullCode = EMPTY_BYTE_ARRAY;
                    if (op == OpCode.CODECOPY)
                        fullCode = program.getCode();

                    if (op == OpCode.EXTCODECOPY) {
                        DataWord address = program.stackPop();
                        fullCode = program.getCodeAt(address);
                    }

                    int memOffset = program.stackPop().intValueSafe();
                    int codeOffset = program.stackPop().intValueSafe();
                    int lengthData = program.stackPop().intValueSafe();

                    int sizeToBeCopied =
                            (long) codeOffset + lengthData > fullCode.length ?
                                    (fullCode.length < codeOffset ? 0 : fullCode.length - codeOffset)
                                    : lengthData;

                    byte[] codeCopy = new byte[lengthData];

                    if (codeOffset < fullCode.length)
                        System.arraycopy(fullCode, codeOffset, codeCopy, 0, sizeToBeCopied);

                    if (logger.isInfoEnabled())
                        hint = "code: " + toHexString(codeCopy);

                    program.memorySave(memOffset, lengthData, codeCopy);
                    program.step();
                }
                break;
                case EXTCODEHASH: {
                    DataWord address = program.stackPop();
                    byte[] codeHash = program.getCodeHashAt(address);
                    program.stackPush(codeHash);
                    program.step();
                }
                break;
                case GASPRICE: {
                    DataWord gasPrice = program.getGasPrice();

                    if (logger.isInfoEnabled())
                        hint = "price: " + gasPrice.toString();

                    program.stackPush(gasPrice);
                    program.step();
                }
                break;

                case TIMESTAMP: {
                    DataWord timestamp = program.getTimestamp();

                    if (logger.isInfoEnabled())
                        hint = "timestamp: " + timestamp.value();

                    program.stackPush(timestamp);
                    program.step();
                }
                break;
                case NUMBER: {
                    DataWord number = program.getTimestamp();

                    if (logger.isInfoEnabled())
                        hint = "number: " + number.value();

                    program.stackPush(number);
                    program.step();
                }
                break;
                case GASLIMIT: {
                    DataWord gaslimit = program.getGasLimit();

                    if (logger.isInfoEnabled())
                        hint = "gaslimit: " + gaslimit;

                    program.stackPush(gaslimit);
                    program.step();
                }
                break;
                case POP: {
                    program.stackPop();
                    program.step();
                }   break;
                case DUP1: case DUP2: case DUP3: case DUP4:
                case DUP5: case DUP6: case DUP7: case DUP8:
                case DUP9: case DUP10: case DUP11: case DUP12:
                case DUP13: case DUP14: case DUP15: case DUP16:{

                    int n = op.val() - OpCode.DUP1.val() + 1;
                    DataWord word_1 = stack.get(stack.size() - n);
                    program.stackPush(word_1);
                    program.step();

                }   break;
                case SWAP1: case SWAP2: case SWAP3: case SWAP4:
                case SWAP5: case SWAP6: case SWAP7: case SWAP8:
                case SWAP9: case SWAP10: case SWAP11: case SWAP12:
                case SWAP13: case SWAP14: case SWAP15: case SWAP16:{

                    int n = op.val() - OpCode.SWAP1.val() + 2;
                    stack.swap(stack.size() - 1, stack.size() - n);
                    program.step();
                }
                break;
                case LOG0:
                case LOG1:
                case LOG2:
                case LOG3:
                case LOG4: {

                    if (program.isStaticCall()) throw new INVEProgram.StaticCallModificationException();
                    DataWord address = program.getOwnerAddress();

                    DataWord memStart = stack.pop();
                    DataWord memOffset = stack.pop();

                    int nTopics = op.val() - OpCode.LOG0.val();

                    List<DataWord> topics = new ArrayList<>();
                    for (int i = 0; i < nTopics; ++i) {
                        DataWord topic = stack.pop();
                        topics.add(topic);
                    }

                    byte[] data = program.memoryChunk(memStart.intValueSafe(), memOffset.intValueSafe());

                    LogInfo logInfo =
                            new LogInfo(address.getLast20Bytes(), topics, data);

                    if (logger.isInfoEnabled())
                        hint = logInfo.toString();

                    program.getResult().addLogInfo(logInfo);
                    program.step();
                }
                break;
                case MLOAD: {
                    DataWord addr = program.stackPop();
                    DataWord data = program.memoryLoad(addr);

                    if (logger.isInfoEnabled())
                        hint = "data: " + data;

                    program.stackPush(data);
                    program.step();
                }
                break;
                case MSTORE: {
                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = "addr: " + addr + " value: " + value;

                    program.memorySave(addr, value);
                    program.step();
                }
                break;
                case MSTORE8: {
                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();
                    byte[] byteVal = {value.getData()[31]};
                    program.memorySave(addr.intValueSafe(), byteVal);
                    program.step();
                }
                break;
                case SLOAD: {
                    DataWord key = program.stackPop();
                    DataWord val = program.storageLoad(key);

                    if (logger.isInfoEnabled())
                        hint = "key: " + key + " value: " + val;

                    if (val == null)
                        val = key.and(DataWord.ZERO);

                    program.stackPush(val);
                    program.step();
                }
                break;
                case SSTORE: {
                    if (program.isStaticCall()) throw new INVEProgram.StaticCallModificationException();

                    DataWord addr = program.stackPop();
                    DataWord value = program.stackPop();

                    if (logger.isInfoEnabled())
                        hint = "[" + program.getOwnerAddress().toPrefixString() + "] key: " + addr + " value: " + value;

                    program.storageSave(addr, value);
                    program.step();
                }
                break;
                case JUMP: {
                    DataWord pos = program.stackPop();
                    int nextPC = program.verifyJumpDest(pos);

                    if (logger.isInfoEnabled())
                        hint = "~> " + nextPC;

                    program.setPC(nextPC);

                }
                break;
                case JUMPI: {
                    DataWord pos = program.stackPop();
                    DataWord cond = program.stackPop();

                    if (!cond.isZero()) {
                        int nextPC = program.verifyJumpDest(pos);

                        if (logger.isInfoEnabled())
                            hint = "~> " + nextPC;

                        program.setPC(nextPC);
                    } else {
                        program.step();
                    }

                }
                break;
                case PC: {
                    int pc = program.getPC();
                    DataWord pcWord = DataWord.of(pc);

                    if (logger.isInfoEnabled())
                        hint = pcWord.toString();

                    program.stackPush(pcWord);
                    program.step();
                }
                break;
                case MSIZE: {
                    int memSize = program.getMemSize();
                    DataWord wordMemSize = DataWord.of(memSize);

                    if (logger.isInfoEnabled())
                        hint = "" + memSize;

                    program.stackPush(wordMemSize);
                    program.step();
                }
                break;
                case GAS: {
                    DataWord gas = program.getGas();

                    if (logger.isInfoEnabled())
                        hint = "" + gas;

                    program.stackPush(gas);
                    program.step();
                }
                break;

                case PUSH1:
                case PUSH2:
                case PUSH3:
                case PUSH4:
                case PUSH5:
                case PUSH6:
                case PUSH7:
                case PUSH8:
                case PUSH9:
                case PUSH10:
                case PUSH11:
                case PUSH12:
                case PUSH13:
                case PUSH14:
                case PUSH15:
                case PUSH16:
                case PUSH17:
                case PUSH18:
                case PUSH19:
                case PUSH20:
                case PUSH21:
                case PUSH22:
                case PUSH23:
                case PUSH24:
                case PUSH25:
                case PUSH26:
                case PUSH27:
                case PUSH28:
                case PUSH29:
                case PUSH30:
                case PUSH31:
                case PUSH32: {
                    program.step();
                    int nPush = op.val() - PUSH1.val() + 1;

                    byte[] data = program.sweep(nPush);

                    if (logger.isInfoEnabled())
                        hint = "" + toHexString(data);

                    program.stackPush(data);
                }
                break;
                case JUMPDEST: {
                    program.step();
                }
                break;
                case CREATE: {
                    if (program.isStaticCall()) throw new INVEProgram.StaticCallModificationException();

                    DataWord value = program.stackPop();
                    DataWord inOffset = program.stackPop();
                    DataWord inSize = program.stackPop();

                    if (logger.isInfoEnabled())
                        logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                                String.format("%-12s", op.name()),
                                program.getGas().value(),
                                program.getCallDeep(), hint);

                    program.createContract(value, inOffset, inSize);

                    program.step();
                }
                break;
                case CREATE2: {
                    if (program.isStaticCall()) throw new INVEProgram.StaticCallModificationException();

                    DataWord value = program.stackPop();
                    DataWord inOffset = program.stackPop();
                    DataWord inSize = program.stackPop();
                    DataWord salt = program.stackPop();

                    if (logger.isInfoEnabled())
                        logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                                String.format("%-12s", op.name()),
                                program.getGas().value(),
                                program.getCallDeep(), hint);

                    program.createContract2(value, inOffset, inSize, salt);

                    program.step();
                }
                break;
                case CALL:
                case CALLCODE:
                case DELEGATECALL:
                case STATICCALL: {
                    program.stackPop(); // use adjustedCallGas instead of requested
                    DataWord codeAddress = program.stackPop();
                    DataWord value = op.callHasValue() ?
                            program.stackPop() : DataWord.ZERO;

                    if (program.isStaticCall() && op == CALL && !value.isZero())
                        throw new INVEProgram.StaticCallModificationException();

                    if (!value.isZero()) {
                        adjustedCallGas = adjustedCallGas.add(DataWord.of(gasCosts.getSTIPEND_CALL()));
                    }

                    DataWord inDataOffs = program.stackPop();
                    DataWord inDataSize = program.stackPop();

                    DataWord outDataOffs = program.stackPop();
                    DataWord outDataSize = program.stackPop();

                    if (logger.isInfoEnabled()) {
                        hint = "addr: " + toHexString(codeAddress.getLast20Bytes())
                                + " gas: " + adjustedCallGas.shortHex()
                                + " inOff: " + inDataOffs.shortHex()
                                + " inSize: " + inDataSize.shortHex();
                        logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                                String.format("%-12s", op.name()),
                                program.getGas().value(),
                                program.getCallDeep(), hint);
                    }

                    program.memoryExpand(outDataOffs, outDataSize);

                    MessageCall msg = new MessageCall(
                            op, adjustedCallGas, codeAddress, value, inDataOffs, inDataSize,
                            outDataOffs, outDataSize);

                    PrecompiledContracts.PrecompiledContract contract =
                            PrecompiledContracts.getContractForAddress(codeAddress, blockchainConfig);

                    if (!op.callIsStateless()) {
                        program.getResult().addTouchAccount(codeAddress.getLast20Bytes());
                    }

                    if (contract != null) {
                        program.callToPrecompiledAddress(msg, contract);
                    } else {
                        program.callToAddress(msg);
                    }

                    program.step();
                }
                break;
                case RETURN:
                case REVERT: {
                    DataWord offset = program.stackPop();
                    DataWord size = program.stackPop();

                    byte[] hReturn = program.memoryChunk(offset.intValueSafe(), size.intValueSafe());
                    program.setHReturn(hReturn);

                    if (logger.isInfoEnabled())
                        hint = "data: " + toHexString(hReturn)
                                + " offset: " + offset.value()
                                + " size: " + size.value();

                    program.step();
                    program.stop();

                    if (op == REVERT) {
                        program.getResult().setRevert();
                    }
                }
                break;
                case SUICIDE: {
                    if (program.isStaticCall()) throw new INVEProgram.StaticCallModificationException();

                    DataWord address = program.stackPop();
                    program.suicide(address);
                    program.getResult().addTouchAccount(address.getLast20Bytes());

                    if (logger.isInfoEnabled())
                        hint = "address: " + toHexString(program.getOwnerAddress().getLast20Bytes());

                    program.stop();
                }
                break;
                default:
                    break;
            }

            program.setPreviouslyExecutedOp(op.val());

            if (logger.isInfoEnabled() && !op.isCall())
                logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                        String.format("%-12s",
                                op.name()), program.getGas().value(),
                        program.getCallDeep(), hint);

            // vmCounter++;
        } catch (RuntimeException e) {
            logger.warn("VM halted: [{}]", e);
            program.spendAllGas();
            program.resetFutureRefund();
            program.stop();
            throw e;
        } finally {
            program.fullTrace();
        }
    }

    public void play(INVEProgram program) {
        if (program.byTestingSuite()) return;

        try {
            if (hasHooks) {
                onHookEvent(hook -> hook.startPlay(program));
            }

            while (!program.isStopped()) {
                this.step(program);
            }

        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        } catch (StackOverflowError soe) {
            logger.error("\n !!! StackOverflowError: update your java run command with -Xss2M (-Xss8M for tests) !!!\nexit...\n", soe);
            System.exit(-1);
        } finally {
            if (hasHooks) {
                onHookEvent(hook -> hook.stopPlay(program));
            }
        }
    }

    /**
     * Utility to calculate new total memory size needed for an operation.
     * <br/> Basically just offset + size, unless size is 0, in which case the result is also 0.
     *
     * @param offset starting position of the memory
     * @param size number of bytes needed
     * @return offset + size, unless size is 0. In that case memNeeded is also 0.
     */
    private static BigInteger memNeeded(DataWord offset, DataWord size) {
        return size.isZero() ? BigInteger.ZERO : offset.value().add(size.value());
    }

    /*
     * Dumping the VM state at the current operation in various styles
     *  - standard  Not Yet Implemented
     *  - standard+ (owner address, program counter, operation, gas left)
     *  - pretty (stack, memory, storage, level, contract,
     *              vmCounter, internalSteps, operation
                    gasBefore, gasCost, memWords)
     */
    // private void dumpLine(OpCode op, long gasBefore, long gasCost, long memWords, INVEProgram program) {
    //     if (config.dumpStyle().equals("standard+")) {
    //         switch (op) {
    //             case STOP:
    //             case RETURN:
    //             case SUICIDE:

    //                 ContractDetails details = program.getStorage()
    //                         .getContractDetails(program.getOwnerAddress().getLast20Bytes());
    //                 List<DataWord> storageKeys = new ArrayList<>(details.getStorage().keySet());
    //                 Collections.sort(storageKeys);

    //                 for (DataWord key : storageKeys) {
    //                     dumpLogger.trace("{} {}",
    //                             toHexString(key.getNoLeadZeroesData()),
    //                             toHexString(details.getStorage().get(key).getNoLeadZeroesData()));
    //                 }
    //                 break;
    //             default:
    //                 break;
    //         }
    //         String addressString = toHexString(program.getOwnerAddress().getLast20Bytes());
    //         String pcString = toHexString(DataWord.of(program.getPC()).getNoLeadZeroesData());
    //         String opString = toHexString(new byte[]{op.val()});
    //         String gasString = toHexString(program.getGas().getNoLeadZeroesData());

    //         dumpLogger.trace("{} {} {} {}", addressString, pcString, opString, gasString);
    //     } else if (config.dumpStyle().equals("pretty")) {
    //         dumpLogger.trace("    STACK");
    //         for (DataWord item : program.getStack()) {
    //             dumpLogger.trace("{}", item);
    //         }
    //         dumpLogger.trace("    MEMORY");
    //         String memoryString = program.memoryToString();
    //         if (!"".equals(memoryString))
    //             dumpLogger.trace("{}", memoryString);

    //         dumpLogger.trace("    STORAGE");
    //         ContractDetails details = program.getStorage()
    //                 .getContractDetails(program.getOwnerAddress().getLast20Bytes());
    //         List<DataWord> storageKeys = new ArrayList<>(details.getStorage().keySet());
    //         Collections.sort(storageKeys);

    //         for (DataWord key : storageKeys) {
    //             dumpLogger.trace("{}: {}",
    //                     key.shortHex(),
    //                     details.getStorage().get(key).shortHex());
    //         }

    //         int level = program.getCallDeep();
    //         String contract = toHexString(program.getOwnerAddress().getLast20Bytes());
    //         String internalSteps = String.format("%4s", Integer.toHexString(program.getPC())).replace(' ', '0').toUpperCase();
    //         dumpLogger.trace("{} | {} | #{} | {} : {} | {} | -{} | {}x32",
    //                 level, contract, vmCounter, internalSteps, op,
    //                 gasBefore, gasCost, memWords);
    //     }
    // }
}
