/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package one.inve.contract.ethplugin.listener;

import one.inve.contract.ethplugin.core.*;

import java.util.List;

/**
 * @author Roman Mandeleil
 * @since 08.08.2014
 */
public class EthereumListenerAdapter implements EthereumListener {

    @Override
    public void trace(String output) {
    }

    public void onBlock(Block block, List<TransactionReceipt> receipts) {
    }

    @Override
    public void onBlock(BlockSummary blockSummary) {
        onBlock(blockSummary.getBlock(), blockSummary.getReceipts());
    }

    @Override
    public void onPeerDisconnect(String host, long port) {
    }

    @Override
    public void onPendingTransactionsReceived(List<Transaction> transactions) {
    }

    @Override
    public void onSyncDone(SyncState state) {

    }

    @Override
    public void onNoConnections() {

    }


    @Override
    public void onVMTraceCreated(String transactionHash, String trace) {

    }

    @Override
    public void onTransactionExecuted(TransactionExecutionSummary summary) {

    }

    @Override
    public void onPendingTransactionUpdate(TransactionReceipt txReceipt, PendingTransactionState state, Block block) {

    }
}
