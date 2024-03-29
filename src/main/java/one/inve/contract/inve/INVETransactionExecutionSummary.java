package one.inve.contract.inve;

import one.inve.contract.ethplugin.core.Transaction;
import one.inve.contract.ethplugin.util.ByteUtil;
import one.inve.contract.ethplugin.util.RLP;
import one.inve.contract.ethplugin.util.RLPElement;
import one.inve.contract.ethplugin.util.RLPList;
import one.inve.contract.ethplugin.vm.DataWord;
import one.inve.contract.ethplugin.vm.LogInfo;
import one.inve.contract.ethplugin.vm.program.InternalTransaction;
import org.springframework.util.Assert;

import java.math.BigInteger;
import java.util.*;

import static java.util.Collections.*;
import static one.inve.contract.ethplugin.util.BIUtil.toBI;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;

public class INVETransactionExecutionSummary {

    private Transaction tx;
    private BigInteger value = BigInteger.ZERO;
    private BigInteger gasPrice = BigInteger.ZERO;
    private BigInteger gasLimit = BigInteger.ZERO;
    private BigInteger gasUsed = BigInteger.ZERO;
    private BigInteger gasLeftover = BigInteger.ZERO;
    private BigInteger gasRefund = BigInteger.ZERO;

    private List<DataWord> deletedAccounts = emptyList();
    private List<InternalTransaction> internalTransactions = emptyList();
    private List<InternalTransferData> balanceChanges =  emptyList();
    private Map<DataWord, DataWord> storageDiff = emptyMap();
    private INVETransactionTouchedStorage touchedStorage = new INVETransactionTouchedStorage();


    private byte[] result;
    private List<LogInfo> logs;

    private boolean failed;

    private byte[] rlpEncoded;
    private boolean parsed;


    public INVETransactionExecutionSummary(Transaction transaction) {
        this.tx = transaction;
        this.gasLimit = toBI(transaction.getGasLimit());
        this.gasPrice = toBI(transaction.getGasPrice());
        this.value = toBI(transaction.getValue());
    }

    public INVETransactionExecutionSummary(byte[] rlpEncoded) {
        this.rlpEncoded = rlpEncoded;
        this.parsed = false;
    }

    public void rlpParse() {
        if (parsed) return;

        RLPList decodedTxList = RLP.decode2(rlpEncoded);
        RLPList summary = (RLPList) decodedTxList.get(0);

        this.tx = new Transaction(summary.get(0).getRLPData());
        this.value = decodeBigInteger(summary.get(1).getRLPData());
        this.gasPrice = decodeBigInteger(summary.get(2).getRLPData());
        this.gasLimit = decodeBigInteger(summary.get(3).getRLPData());
        this.gasUsed = decodeBigInteger(summary.get(4).getRLPData());
        this.gasLeftover = decodeBigInteger(summary.get(5).getRLPData());
        this.gasRefund = decodeBigInteger(summary.get(6).getRLPData());
        this.deletedAccounts = decodeDeletedAccounts((RLPList) summary.get(7));
        this.internalTransactions = decodeInternalTransactions((RLPList) summary.get(8));
        this.touchedStorage = decodeTouchedStorage(summary.get(9));
        this.result = summary.get(10).getRLPData();
        this.logs = decodeLogs((RLPList) summary.get(11));
        byte[] failed = summary.get(12).getRLPData();
        this.failed = isNotEmpty(failed) && RLP.decodeInt(failed, 0) == 1;
    }

    private static BigInteger decodeBigInteger(byte[] encoded) {
        return ByteUtil.bytesToBigInteger(encoded);
    }

    public byte[] getEncoded() {
        if (rlpEncoded != null) return rlpEncoded;


        this.rlpEncoded = RLP.encodeList(
                this.tx.getEncoded(),
                RLP.encodeBigInteger(this.value),
                RLP.encodeBigInteger(this.gasPrice),
                RLP.encodeBigInteger(this.gasLimit),
                RLP.encodeBigInteger(this.gasUsed),
                RLP.encodeBigInteger(this.gasLeftover),
                RLP.encodeBigInteger(this.gasRefund),
                encodeDeletedAccounts(this.deletedAccounts),
                encodeInternalTransactions(this.internalTransactions),
                encodeTouchedStorage(this.touchedStorage),
                RLP.encodeElement(this.result),
                encodeLogs(this.logs),
                RLP.encodeInt(this.failed ? 1 : 0)
        );

        return rlpEncoded;
    }

    public static byte[] encodeTouchedStorage(INVETransactionTouchedStorage touchedStorage) {
        Collection<INVETransactionTouchedStorage.Entry> entries = touchedStorage.getEntries();
        byte[][] result = new byte[entries.size()][];

        int i = 0;
        for (INVETransactionTouchedStorage.Entry entry : entries) {
            byte[] key = RLP.encodeElement(entry.getKey().getData());
            byte[] value = RLP.encodeElement(entry.getValue().getData());
            byte[] changed = RLP.encodeInt(entry.isChanged() ? 1 : 0);

            result[i++] = RLP.encodeList(key, value, changed);
        }

        return RLP.encodeList(result);
    }

    public static INVETransactionTouchedStorage decodeTouchedStorage(RLPElement encoded) {
        INVETransactionTouchedStorage result = new INVETransactionTouchedStorage();

        for (RLPElement entry : (RLPList) encoded) {
            RLPList asList = (RLPList) entry;

            DataWord key = DataWord.of(asList.get(0).getRLPData());
            DataWord value = DataWord.of(asList.get(1).getRLPData());
            byte[] changedBytes = asList.get(2).getRLPData();
            boolean changed = isNotEmpty(changedBytes) && RLP.decodeInt(changedBytes, 0) == 1;

            result.add(new INVETransactionTouchedStorage.Entry(key, value, changed));
        }

        return result;
    }

    private static List<LogInfo> decodeLogs(RLPList logs) {
        ArrayList<LogInfo> result = new ArrayList<>();
        for (RLPElement log : logs) {
            result.add(new LogInfo(log.getRLPData()));
        }
        return result;
    }

    private static byte[] encodeLogs(List<LogInfo> logs) {
        byte[][] result = new byte[logs.size()][];
        for (int i = 0; i < logs.size(); i++) {
            LogInfo log = logs.get(i);
            result[i] = log.getEncoded();
        }

        return RLP.encodeList(result);
    }

    private static byte[] encodeInternalTransactions(List<InternalTransaction> internalTransactions) {
        byte[][] result = new byte[internalTransactions.size()][];
        for (int i = 0; i < internalTransactions.size(); i++) {
            InternalTransaction transaction = internalTransactions.get(i);
            result[i] = transaction.getEncoded();
        }

        return RLP.encodeList(result);
    }

    private static List<InternalTransaction> decodeInternalTransactions(RLPList internalTransactions) {
        List<InternalTransaction> result = new ArrayList<>();
        for (RLPElement internalTransaction : internalTransactions) {
            result.add(new InternalTransaction(internalTransaction.getRLPData()));
        }
        return result;
    }

    private static byte[] encodeDeletedAccounts(List<DataWord> deletedAccounts) {
        byte[][] result = new byte[deletedAccounts.size()][];
        for (int i = 0; i < deletedAccounts.size(); i++) {
            DataWord deletedAccount = deletedAccounts.get(i);
            result[i] = RLP.encodeElement(deletedAccount.getData());

        }
        return RLP.encodeList(result);
    }

    private static List<DataWord> decodeDeletedAccounts(RLPList deletedAccounts) {
        List<DataWord> result = new ArrayList<>();
        for (RLPElement deletedAccount : deletedAccounts) {
            result.add(DataWord.of(deletedAccount.getRLPData()));
        }
        return result;
    }

    public Transaction getTransaction() {
        if (!parsed) rlpParse();
        return tx;
    }

    public byte[] getTransactionHash() {
        return getTransaction().getHash();
    }

    private BigInteger calcCost(BigInteger gas) {
        return gasPrice.multiply(gas);
    }

    public BigInteger getFee() {
        if (!parsed) rlpParse();
        return calcCost(gasLimit.subtract(gasLeftover.add(gasRefund)));
    }

    public BigInteger getRefund() {
        if (!parsed) rlpParse();
        return calcCost(gasRefund);
    }

    public BigInteger getLeftover() {
        if (!parsed) rlpParse();
        return calcCost(gasLeftover);
    }

    public BigInteger getGasPrice() {
        if (!parsed) rlpParse();
        return gasPrice;
    }

    public BigInteger getGasLimit() {
        if (!parsed) rlpParse();
        return gasLimit;
    }

    public BigInteger getGasUsed() {
        if (!parsed) rlpParse();
        return gasUsed;
    }

    public BigInteger getGasLeftover() {
        if (!parsed) rlpParse();
        return gasLeftover;
    }

    public BigInteger getValue() {
        if (!parsed) rlpParse();
        return value;
    }

    public List<DataWord> getDeletedAccounts() {
        if (!parsed) rlpParse();
        return deletedAccounts;
    }

    public List<InternalTransaction> getInternalTransactions() {
        if (!parsed) rlpParse();
        return internalTransactions;
    }

    public List<InternalTransferData> getBalanceChanges() {
        return balanceChanges;
    }

    @Deprecated
    /* Use getTouchedStorage().getAll() instead */
    public Map<DataWord, DataWord> getStorageDiff() {
        if (!parsed) rlpParse();
        return storageDiff;
    }

    public BigInteger getGasRefund() {
        if (!parsed) rlpParse();
        return gasRefund;
    }

    public boolean isFailed() {
        if (!parsed) rlpParse();
        return failed;
    }

    public byte[] getResult() {
        if (!parsed) rlpParse();
        return result;
    }

    public List<LogInfo> getLogs() {
        if (!parsed) rlpParse();
        return logs;
    }

    public INVETransactionTouchedStorage getTouchedStorage() {
        return touchedStorage;
    }

    public static Builder builderFor(Transaction transaction) {
        return new Builder(transaction);
    }

    public static class Builder {

        private final INVETransactionExecutionSummary summary;

        Builder(Transaction transaction) {
            Assert.notNull(transaction, "Cannot build TransactionExecutionSummary for null transaction.");
            summary = new INVETransactionExecutionSummary(transaction);
        }

        public Builder gasUsed(BigInteger gasUsed) {
            summary.gasUsed = gasUsed;
            return this;
        }

        public Builder gasLeftover(BigInteger gasLeftover) {
            summary.gasLeftover = gasLeftover;
            return this;
        }

        public Builder gasRefund(BigInteger gasRefund) {
            summary.gasRefund = gasRefund;
            return this;
        }

        public Builder internalTransactions(List<InternalTransaction> internalTransactions) {
            summary.internalTransactions = unmodifiableList(internalTransactions);
            return this;
        }

        public Builder balanceChanges(List<InternalTransferData> internalTransferList) {
            summary.balanceChanges = unmodifiableList(internalTransferList);
            return this;
        }

        public Builder deletedAccounts(Set<DataWord> deletedAccounts) {
            summary.deletedAccounts = new ArrayList<>();
            for (DataWord account : deletedAccounts) {
                summary.deletedAccounts.add(account);
            }
            return this;
        }

        public Builder storageDiff(Map<DataWord, DataWord> storageDiff) {
            summary.storageDiff = unmodifiableMap(storageDiff);
            return this;
        }

        public Builder touchedStorage(Map<DataWord, DataWord> touched, Map<DataWord, DataWord> changed) {
            summary.touchedStorage.addReading(touched);
            summary.touchedStorage.addWriting(changed);
            return this;
        }

        public Builder markAsFailed() {
            summary.failed = true;
            return this;
        }

        public Builder logs(List<LogInfo> logs) {
            summary.logs = logs;
            return this;
        }

        public Builder result(byte[] result) {
            summary.result = result;
            return this;
        }

        public INVETransactionExecutionSummary build() {
            summary.parsed = true;
            if (summary.failed) {
                for (InternalTransaction transaction : summary.internalTransactions) {
                    transaction.reject();
                }
            }
            return summary;
        }
    }
}
