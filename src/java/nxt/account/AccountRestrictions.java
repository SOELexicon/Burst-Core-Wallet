/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

package nxt.account;

import nxt.Nxt;
import nxt.NxtException;
import nxt.NxtException.AccountControlException;
import nxt.blockchain.ChildChain;
import nxt.blockchain.ChildTransaction;
import nxt.blockchain.FxtTransaction;
import nxt.blockchain.Transaction;
import nxt.blockchain.TransactionType;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.DbUtils;
import nxt.db.VersionedEntityDbTable;
import nxt.util.Convert;
import nxt.util.Logger;
import nxt.voting.AccountControlTransactionType;
import nxt.voting.PhasingAppendix;
import nxt.voting.PhasingParams;
import nxt.voting.SetPhasingOnlyAttachment;
import nxt.voting.VoteWeighting.VotingModel;
import nxt.voting.VotingTransactionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public final class AccountRestrictions {

    public static final class PhasingOnly {

        public static PhasingOnly get(long accountId) {
            return phasingControlTable.get(phasingControlDbKeyFactory.newKey(accountId));
        }

        public static int getCount() {
            return phasingControlTable.getCount();
        }

        public static DbIterator<PhasingOnly> getAll(int from, int to) {
            return phasingControlTable.getAll(from, to);
        }

        public static void set(Account senderAccount, SetPhasingOnlyAttachment attachment) {
            PhasingParams phasingParams = attachment.getPhasingParams();
            if (phasingParams.getVoteWeighting().getVotingModel() == VotingModel.NONE) {
                //no voting - remove the control
                senderAccount.removeControl(Account.ControlType.PHASING_ONLY);
                PhasingOnly phasingOnly = get(senderAccount.getId());
                phasingControlTable.delete(phasingOnly);
            } else {
                senderAccount.addControl(Account.ControlType.PHASING_ONLY);
                PhasingOnly phasingOnly = get(senderAccount.getId());
                if (phasingOnly == null) {
                    phasingOnly = new PhasingOnly(senderAccount.getId(), phasingParams, attachment.getMaxFees(),
                            attachment.getMinDuration(), attachment.getMaxDuration());
                } else {
                    phasingOnly.phasingParams = phasingParams;
                    phasingOnly.maxFees = attachment.getMaxFees();
                    phasingOnly.minDuration = attachment.getMinDuration();
                    phasingOnly.maxDuration = attachment.getMaxDuration();
                }
                phasingControlTable.insert(phasingOnly);
            }
        }

        private final DbKey dbKey;
        private final long accountId;
        private PhasingParams phasingParams;
        private Map<Integer,Long> maxFees;
        private short minDuration;
        private short maxDuration;

        private PhasingOnly(long accountId, PhasingParams params, Map<Integer, Long> maxFees, short minDuration, short maxDuration) {
            this.accountId = accountId;
            dbKey = phasingControlDbKeyFactory.newKey(this.accountId);
            phasingParams = params;
            this.maxFees = maxFees;
            this.minDuration = minDuration;
            this.maxDuration = maxDuration;
        }

        private PhasingOnly(ResultSet rs, DbKey dbKey) throws SQLException {
            this.accountId = rs.getLong("account_id");
            this.dbKey = dbKey;
            Long[] whitelist = DbUtils.getArray(rs, "whitelist", Long[].class);
            phasingParams = new PhasingParams(rs.getByte("voting_model"),
                    rs.getLong("holding_id"),
                    rs.getLong("quorum"),
                    rs.getLong("min_balance"),
                    rs.getByte("min_balance_model"),
                    whitelist == null ? Convert.EMPTY_LONG : Convert.toArray(whitelist));
            Integer[] chainIds = DbUtils.getArray(rs, "max_fees_chains", Integer[].class);
            Long[] chainMaxFees = DbUtils.getArray(rs, "max_fees", Long[].class);
            this.maxFees = new HashMap<>(chainIds.length);
            for (int i = 0; i < chainIds.length; i++) {
                this.maxFees.put(chainIds[i], chainMaxFees[i]);
            }
            this.minDuration = rs.getShort("min_duration");
            this.maxDuration = rs.getShort("max_duration");
        }

        public long getAccountId() {
            return accountId;
        }

        public PhasingParams getPhasingParams() {
            return phasingParams;
        }

        public Map<Integer,Long> getMaxFees() {
            return maxFees;
        }

        public short getMinDuration() {
            return minDuration;
        }

        public short getMaxDuration() {
            return maxDuration;
        }

        private void checkTransaction(ChildTransaction transaction, boolean validatingAtFinish) throws AccountControlException {
            ChildChain childChain = transaction.getChain();
            long maxFee = Convert.nullToZero(maxFees.get(childChain.getId()));
            if (!validatingAtFinish && maxFee > 0) {
                long totalFee = Math.addExact(transaction.getFee(),
                        childChain.getPhasingPollHome().getSenderPhasedTransactionFees(transaction.getSenderId()));
                if (totalFee > maxFee) {
                    throw new AccountControlException(String.format("Maximum total fees limit of %f %s exceeded, total fees are %f %s",
                            ((double) maxFee) / childChain.ONE_COIN, childChain.getName(), ((double)totalFee) / childChain.ONE_COIN, childChain.getName()));
                }
            }
            if (transaction.getType() == VotingTransactionType.PHASING_VOTE_CASTING) {
                return;
            }
            try {
                phasingParams.checkApprovable();
            } catch (NxtException.NotCurrentlyValidException e) {
                Logger.logDebugMessage("Account control no longer valid: " + e.getMessage());
                return;
            }
            PhasingAppendix phasingAppendix = transaction.getPhasing();
            if (phasingAppendix == null) {
                throw new AccountControlException("Non-phased transaction when phasing account control is enabled");
            }
            if (!phasingParams.equals(phasingAppendix.getParams())) {
                throw new AccountControlException("Phasing parameters mismatch phasing account control. Expected: " +
                        phasingParams.toString() + " . Actual: " + phasingAppendix.getParams().toString());
            }
            if (!validatingAtFinish) {
                int duration = phasingAppendix.getFinishHeight() - Nxt.getBlockchain().getHeight();
                if ((maxDuration > 0 && duration > maxDuration) || (minDuration > 0 && duration < minDuration)) {
                    throw new AccountControlException("Invalid phasing duration " + duration);
                }
            }
        }

        private void save(Connection con) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO account_control_phasing "
                    + "(account_id, whitelist, voting_model, quorum, min_balance, holding_id, min_balance_model, "
                    + "max_fees_chains, max_fees, min_duration, max_duration, height, latest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)")) {
                int i = 0;
                pstmt.setLong(++i, this.accountId);
                DbUtils.setArrayEmptyToNull(pstmt, ++i, Convert.toArray(phasingParams.getWhitelist()));
                pstmt.setByte(++i, phasingParams.getVoteWeighting().getVotingModel().getCode());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getQuorum());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getVoteWeighting().getMinBalance());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getVoteWeighting().getHoldingId());
                pstmt.setByte(++i, phasingParams.getVoteWeighting().getMinBalanceModel().getCode());
                Integer[] chainIds = new Integer[maxFees.size()];
                Long[] chainMaxFees = new Long[maxFees.size()];
                int j = 0;
                for (Map.Entry<Integer,Long> entry : maxFees.entrySet()) {
                    chainIds[j] = entry.getKey();
                    chainMaxFees[j] = entry.getValue();
                    j++;
                }
                DbUtils.setArray(pstmt, ++i, chainIds);
                DbUtils.setArray(pstmt, ++i, chainMaxFees);
                pstmt.setShort(++i, this.minDuration);
                pstmt.setShort(++i, this.maxDuration);
                pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }

    }

    private static final DbKey.LongKeyFactory<PhasingOnly> phasingControlDbKeyFactory = new DbKey.LongKeyFactory<PhasingOnly>("account_id") {
        @Override
        public DbKey newKey(PhasingOnly rule) {
            return rule.dbKey;
        }
    };

    private static final VersionedEntityDbTable<PhasingOnly> phasingControlTable = new VersionedEntityDbTable<PhasingOnly>("public.account_control_phasing", phasingControlDbKeyFactory) {

        @Override
        protected PhasingOnly load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new PhasingOnly(rs, dbKey);
        }

        @Override
        protected void save(Connection con, PhasingOnly phasingOnly) throws SQLException {
            phasingOnly.save(con);
        }
    };

    public static void init() {
    }

    public static void checkTransaction(ChildTransaction transaction, boolean validatingAtFinish) throws NxtException.NotCurrentlyValidException {
        Account senderAccount = Account.getAccount(transaction.getSenderId());
        if (senderAccount == null) {
            throw new NxtException.NotCurrentlyValidException("Account " + Long.toUnsignedString(transaction.getSenderId()) + " does not exist yet");
        }
        if (senderAccount.getControls().contains(Account.ControlType.PHASING_ONLY)) {
            PhasingOnly phasingOnly = PhasingOnly.get(transaction.getSenderId());
            phasingOnly.checkTransaction(transaction, validatingAtFinish);
        }
    }

    public static void checkTransaction(FxtTransaction transaction) throws NxtException.NotCurrentlyValidException {
        Account senderAccount = Account.getAccount(transaction.getSenderId());
        if (senderAccount == null) {
            throw new NxtException.NotCurrentlyValidException("Account " + Long.toUnsignedString(transaction.getSenderId()) + " does not exist yet");
        }
        if (senderAccount.getControls().contains(Account.ControlType.PHASING_ONLY)) {
            throw new AccountControlException("Account " + Long.toUnsignedString(transaction.getSenderId())
                    + " is under account control and cannot submit forging chain transactions");
        }
    }

    public static boolean isBlockDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
        Account senderAccount = Account.getAccount(transaction.getSenderId());
        if (!senderAccount.getControls().contains(Account.ControlType.PHASING_ONLY)) {
            return false;
        }
        if (PhasingOnly.get(transaction.getSenderId()).getMaxFees().get(transaction.getChain().getId()) == null) {
            return false;
        }
        return transaction.getType() != AccountControlTransactionType.SET_PHASING_ONLY &&
                TransactionType.isDuplicate(AccountControlTransactionType.SET_PHASING_ONLY, Long.toUnsignedString(senderAccount.getId()),
                        duplicates, true);
    }

}
