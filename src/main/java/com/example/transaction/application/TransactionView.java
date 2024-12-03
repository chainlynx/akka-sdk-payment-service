package com.example.transaction.application;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

//@ComponentId("transaction_view")
public class TransactionView /* extends View */ {

    //@Consume.FromKeyValueEntity(TransactionLogger.class)
//    public static class TransactionLoggerRecorder extends TableUpdater<Transaction> {
//        public Effect<Transaction> onUpdate(TransactionLogger.State state) {
//            return effects().updateRow(
//                new Transaction(
//                    state.txId(),
//                    state.processId(),
//                    state.txStart(),
//                    state.txEnd(),
//                    state.txDuration()
//                )
//            );
//        }
//    }

//    @Query("""
//        SELECT COUNT(*) as txCount,
//        AVG(duration) as avgDuration,
//        MIN(duration) as minDuration,
//        MAX(duration) as maxDuration
//        FROM transaction_view
//        WHERE processId = :processId
//        """)
//    public QueryEffect<TransactionSummary> getTransactionSummary(String processId) {
//        return queryResult();
//    }

    public record TransactionSummary(int txCount, int avgDuration, int minDuration, int maxDuration) {}

    public record Transaction(String txId, String processId, long start, long end, long duration) {}

}
