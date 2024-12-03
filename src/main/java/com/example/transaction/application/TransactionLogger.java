package com.example.transaction.application;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ComponentId("transaction-logger")
public class TransactionLogger extends KeyValueEntity<TransactionLogger.State> {

    private static final Logger log = LoggerFactory.getLogger(TransactionLogger.class);

    @Override
    public State emptyState() { return State.emptyState(); }

    public Effect<Message> log(Log.Entry transaction) {
        log.info("Recording Transaction: {}", transaction);

        if(!transaction.status().equals("TRANSACTION_COMPLETED")) {
            return effects().reply(new Message("Not Logging Transaction"));
        }

        return effects()
            .updateState(
                currentState().log(
                    transaction.processId,
                    transaction.txDuration
                )
            )
            .thenReply(new Message("Transaction Logged"));
    }

    public Effect<State> get() {
        return effects().reply(currentState());
    }

    public record State(
        String processId,
        long minDuration,
        long maxDuration,
        long sumDuration,
        long avgDuration,
        int count) {

        public static State emptyState() {
            return new State("", 0L, 0L, 0L, 0L, 0);
        }

        public State log(String processId, long txDuration) {
            var min = (this.count == 0) ? txDuration : Math.min(txDuration, minDuration);
            var max = Math.max(txDuration, maxDuration);
            var sum = sumDuration + txDuration;
            var count = this.count + 1;
            var avg = sum / count;
            return new State(processId, min, max, sum, avg, count);
        }

    }

    public sealed interface Log {
        record Entry(String txId, String processId, String status, long txStart, long txEnd, long txDuration) implements Log {}
    }

    public record Message(String message) {}


}
