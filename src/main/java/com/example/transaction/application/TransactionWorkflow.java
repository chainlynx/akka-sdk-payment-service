package com.example.transaction.application;

import com.example.mock.*;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.annotations.ComponentId;
import com.example.transaction.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.example.transaction.application.TransactionWorkflow.State.Status.*;
import static com.example.transaction.domain.Transaction.Response.*;
import static com.example.mock.Validation.Validate;
import static com.example.mock.Validation.ValidationResult;
import static com.example.mock.Sanction.Check;
import static com.example.mock.Sanction.SanctionResult;
import static com.example.mock.Liquidity.LiquidityResult;
import static java.time.Duration.ofSeconds;

@ComponentId("transaction")
public class TransactionWorkflow extends Workflow<TransactionWorkflow.State> {

    private static final Logger log = LoggerFactory.getLogger(TransactionWorkflow.class);

    final private ComponentClient client;

    public TransactionWorkflow(ComponentClient client) {
        this.client = client;
    }

    @Override
    public WorkflowDef<State> definition() {

        Step validationCheck = step("validate-transaction")
            .asyncCall(Validate.Transaction.class, cmd -> {
                log.info("Validating Payment Request: {}", cmd);
                return Validation.validate(cmd, client);
            })
            .andThen(ValidationResult.class, validationResult -> switch(validationResult) {
                case ValidationResult.Approved __ -> {
                    var state = currentState();
                    var sanctionCheck = new Check.Accounts(
                        state.txId(),
                        state.transaction().from(), //checking source account
                        state.transaction().to()    //checking destination account
                    );
                    log.info("Validation Request Approved: {}", state.txId());
                    yield effects()
                        .updateState(
                            state.logStep("validate-transaction", "approved")
                                 .withStatus(CHECKING_SANCTIONS)
                        )
                        .transitionTo("sanction-check", sanctionCheck);
                }
                case ValidationResult.Rejected rejected -> {
                    log.warn("Validation Request Rejected: {}", rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("validate-transaction", "rejected")
                                .withStatus(VALIDATION_FAILED)
                        )
                        .end();
                }
            });

        Step sanctionCheck = step("sanction-check")
            .asyncCall(Check.Accounts.class, cmd -> {
                log.info("Checking Sanctions: " + cmd);
                return Sanction.check(cmd);
            })
            .andThen(SanctionResult.class, sanctionResult -> switch(sanctionResult) {
                case SanctionResult.Approved __ -> {
                    var state = currentState();
                    var liquidityCheck = new Liquidity.Verify.Funds(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.info("Sanction Check Approved");
                    yield effects()
                        .updateState(
                            state.logStep("sanction-check", "approved")
                                 .withStatus(VERIFYING_LIQUIDITY)
                        )
                        .transitionTo("liquidity-check", liquidityCheck);
                }
                case SanctionResult.Rejected rejected -> {
                    log.warn("Sanction Check Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("sanction-check", "rejected")
                                .withStatus(SANCTIONS_FAILED)
                        )
                        .end();
                }
            });

        Step liquidityCheck = step("liquidity-check")
            .asyncCall(Liquidity.Verify.Funds.class, cmd -> {
                log.info("Verifying Liquidity: " + cmd);
                return Liquidity.verify(cmd, client);
            })
            .andThen(LiquidityResult.class, liquidityResult -> switch(liquidityResult) {
                case LiquidityResult.Approved __ -> {
                    var state = currentState();
                    var postFunds = new Posting.Post.Funds(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.info("Liquidity Check Approved");
                    yield effects()
                        .updateState(
                            state.logStep("liquidity-check", "approved")
                                 .withStatus(POSTING_TRANSACTION)
                        )
                        .transitionTo("posting-transaction", postFunds);
                }
                case LiquidityResult.Rejected rejected -> {
                    log.warn("Liquidity Check Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("liquidity-check", "rejected")
                                .withStatus(LIQUIDITY_FAILED)
                        )
                        .end();
                }
            });

        Step posting = step("posting-transaction")
            .asyncCall(Posting.Post.Funds.class, cmd -> {
                log.info("Posting Transaction: " + cmd);
                return Posting.post(cmd, client);
            })
            .andThen(Posting.PostResult.class, postingResult -> switch(postingResult) {
                case Posting.PostResult.Approved __ -> {
                    var state = currentState();
                    var clearing = new Clearing.Clear.Funds(
                        state.txId(),
                        state.transaction().to(),
                        state.transaction().amount()
                    );
                    log.info("Transaction Posted");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("posting-transaction", "approved")
                                .withStatus(CLEARING_TRANSACTION)
                        )
                        .transitionTo("transaction-clearing", clearing);
                }
                case Posting.PostResult.Rejected rejected -> {
                    log.warn("Transaction Posting Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("posting-transaction", "rejected")
                                .withStatus(POSTING_FAILED)
                        )
                        .end();
                }
            });

        Step clearing = step("transaction-clearing")
            .asyncCall(Clearing.Clear.Funds.class, cmd -> {
                log.info("Clearing Transaction: " + cmd);
                return Clearing.clear(cmd, client);
            })
            .andThen(Clearing.ClearingResult.class, clearingResult -> switch(clearingResult) {
                case Clearing.ClearingResult.Accepted __ -> {
                    log.info("Transaction Cleared");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("transaction-clearing", "approved")
                                .complete()
                                .withStatus(TRANSACTION_COMPLETED)
                        )
                        .transitionTo("log-transaction");
                }
                case Clearing.ClearingResult.Rejected rejected -> {
                    var state = currentState();
                    var reversal = new Posting.Post.Reversal(
                        state.txId(),
                        state.transaction().from(),
                        state.transaction().amount()
                    );
                    log.warn("Transaction Clearing Rejected: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("transaction-clearing", "rejected")
                                .withStatus(CLEARING_FAILED)
                        )
                        .transitionTo("compensate", reversal);
                }
            });

        Step compensate = step("compensate")
            .asyncCall(Posting.Post.Reversal.class, cmd -> {
                log.info("Compensation");
                return Posting.reversal(cmd, client);
            })
            .andThen(Posting.PostResult.class, postingResult -> switch(postingResult) {
                case Posting.PostResult.Approved __ -> {
                    log.info("Compensation completed");
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("compensate", "approved")
                                .complete()
                                .withStatus(COMPENSATION_COMPLETED)
                        )
                        .transitionTo("log-transaction");
                }
                case Posting.PostResult.Rejected rejected -> {
                    log.warn("Compensation failed: " + rejected.reason());
                    yield effects()
                        .updateState(
                            currentState()
                                .logStep("compensate", "rejected")
                                .complete()
                                .withStatus(TRANSACTION_FAILED)
                        )
                        .transitionTo("log-transaction");
                }
            });

        Step logger = step("log-transaction")
            .asyncCall(() -> {
                log.info("Logging transaction: {}", currentState().txId());
                var current = currentState();
                var logEntry = new TransactionLogger.Log.Entry(
                    current.txId(),
                    current.processId(),
                    current.status().name(),
                    current.started(),
                    current.ended(),
                    current.duration()
                );
                return client.forKeyValueEntity(current.processId())
                    .method(TransactionLogger::log)
                    .invokeAsync(logEntry);
            })
            .andThen(TransactionLogger.Message.class, response -> {
                log.info("Transaction logged: {}", response);
                return effects().end();
            });

        Step failoverHandler = step("failover-handler")
            .asyncCall(() -> {
                log.info("Running workflow failed step for txId: " + currentState().txId());
                return CompletableFuture.completedStage("handling failure...");
            })
            .andThen(String.class, __ -> effects()
                .updateState(
                    currentState()
                        .logStep("failover-handler", "handling failure")
                        .complete()
                        .withStatus(TRANSACTION_FAILED)
                )
                .transitionTo("log-transaction")
            )
            .timeout(ofSeconds(1));

        return workflow()
            .timeout(ofSeconds(60))
            .defaultStepTimeout(ofSeconds(30))
            .failoverTo("failover-handler", maxRetries(0))
            .defaultStepRecoverStrategy(maxRetries(1).failoverTo("failover-handler"))
            .addStep(validationCheck)
            .addStep(sanctionCheck)
            .addStep(liquidityCheck)
            .addStep(posting)
            .addStep(clearing, maxRetries(2).failoverTo("compensate"))
            .addStep(compensate)
            .addStep(failoverHandler)
            .addStep(logger);
    }

    /**
     * By virtue of using the Workflow class with a provided transactionId, any
     * duplicate transaction request, will come to the same workflow, and we can
     * determine the transaction status based on the current state of the workflow.
     */
    public Effect<Response> process(Transaction.Request request) {
        var txId = commandContext().workflowId();
        var current = currentState();

        if (current != null) return effects().reply(respond(current, Status.DUPLICATE));

        var initialized = State.from(txId, request).withStatus(VALIDATING_REQUEST);
        var validateRequest = new Validate.Transaction(
            txId,
            request.from(),
            request.to(),
            request.amount()
        );

        return effects()
            .updateState(initialized)
            .transitionTo("validate-transaction", validateRequest)
            .thenReply(respond(initialized, Status.OK));
    }

    public Effect<State> get() {
        if (currentState() == null) {
            return effects().error("transaction not started");
        } else {
            return effects().reply(currentState());
        }
    }

    private static Response respond(State state, Status status) {
        return switch(status) {
            case OK -> new Received(state.txId(), state.status().name(), state.started());
            case DUPLICATE -> new Processing(state.txId(), state.status().name(), "Duplicate Request: Transaction already handled.");
            case ERROR -> new Processing(state.txId(), state.status().name(), "Transaction failed.");
        };
    }

    private enum Status {
        OK,
        DUPLICATE,
        ERROR
    }

    public record State(
        String txId,
        String processId,
        Transaction transaction,
        Status status,
        Long started,
        Long ended,
        Long duration,
        StepStack history
    ) {

        public record Transaction(String from, String to, int amount) {}

        public record StepStack(List<StepEntry> steps) {

            public StepStack() { this(List.of()); }

            public StepStack push(StepEntry step) {
                return new StepStack(Stream.concat(steps.stream(), Stream.of(step)).toList());
            }

        }

        public record StepEntry(String name, String status, Long finished) {}

        public enum Status {
            INITIALIZING_TRANSACTION,
            VALIDATING_REQUEST,
            VALIDATION_FAILED,
            VERIFYING_LIQUIDITY,
            LIQUIDITY_FAILED,
            POSTING_TRANSACTION,
            POSTING_FAILED,
            CLEARING_TRANSACTION,
            CLEARING_FAILED,
            CHECKING_SANCTIONS,
            SANCTIONS_FAILED,
            TRANSACTION_COMPLETED,
            TRANSACTION_FAILED,
            COMPENSATION_COMPLETED
        }

        public State withStatus(Status newStatus) {
            return new State(txId, processId, transaction, newStatus, started, ended, duration, history);
        }

        public State complete() {
            var ended = System.currentTimeMillis();
            var duration = ended - started;
            return new State(txId, processId, transaction, status, started, ended, duration, history);
        }

        public static State from(String txId, com.example.transaction.domain.Transaction.Request request) {
            return new State(
                txId,
                request.processId(),
                new Transaction(request.from(), request.to(), request.amount()),
                INITIALIZING_TRANSACTION,
                System.currentTimeMillis(),
                0L,
                0L,
                new StepStack()
            );
        }

        public State logStep(String stepName, String stepStatus) {
            return new State(
                txId,
                processId,
                transaction,
                status,
                started,
                ended,
                duration,
                history.push(new StepEntry(stepName, stepStatus, System.currentTimeMillis()))
            );
        }

    }

}
