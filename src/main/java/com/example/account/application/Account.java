package com.example.account.application;

import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import com.example.util.Validator;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.example.util.Validator.*;

@ComponentId("account")
public class Account extends EventSourcedEntity<Account.State, Account.Event> {

    private static final Logger log = LoggerFactory.getLogger(Account.class);

    @Override
    public State emptyState() { return State.emptyState(); }

    public Effect<String> create(int initBalance) {
        return Validator
            .validate(
                isFalse(currentState().isEmpty(), "Account Already Exists")
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .persist(new Event.AccountCreated(commandContext().entityId(), initBalance))
                    .thenReply(__ -> "ok");
                case ERROR -> effects().error(err);
            });
    }

    public Effect<DepositResult> deposit(int amount) {
        State current = currentState();
        State updated = current.deposit(amount);
        return Validator
            .validate(
                isTrue(current.isEmpty(), "Account [" + commandContext().entityId() + "] Doesn't Exist")
            )
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .persist(new Event.FundsDeposited(updated.balance, current.balance))
                    .thenReply(__ -> new DepositResult.DepositSucceed());
                case ERROR -> effects()
                    .reply(new DepositResult.DepositFailed(err));
            });
    }

    public Effect<WithdrawResult> withdraw(int amount) {
        State current = currentState();
        State updated = current.withdraw(amount);
        return Validator
            .validate(
                isTrue(current.isEmpty(), "Account [" + commandContext().entityId() + "] Doesn't Exist"),
                isLtZero(updated.balance, "Insufficient funds")
            )
            .mode(Mode.FAIL_FAST)
            .handle((result, err) -> switch(result){
                case SUCCESS -> effects()
                    .persist(new Event.FundsWithdrawn(updated.balance, current.balance))
                    .thenReply(__ -> new WithdrawResult.WithdrawSucceed());
                case ERROR -> effects()
                    .reply(new WithdrawResult.WithdrawFailed(err));
            });
    }

    public Effect<Integer> get(){
        if(currentState().isEmpty())
            return effects().error("Account Not Found");
        return effects().reply(currentState().balance);
    }

    public Effect<Boolean> verifyFunds(int amount){
        return effects().reply(currentState().balance >= amount);
    }

    @Override
    public State applyEvent(Event event) {
        return switch(event) {
            case Event.AccountCreated c -> new State(eventContext().entityId(), c.initBalance());
            case Event.FundsDeposited d -> new State(eventContext().entityId(), d.newBalance());
            case Event.FundsWithdrawn w -> new State(eventContext().entityId(), w.newBalance());
        };
    }

    public sealed interface Event {

        @TypeName("account-created")
        record AccountCreated(String id, int initBalance) implements Event {}

        @TypeName("funds-deposited")
        record FundsDeposited(int newBalance, int prevBalance) implements Event {}

        @TypeName("funds-withdrawn")
        record FundsWithdrawn(int newBalance, int prevBalance) implements Event {}

    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Account.WithdrawResult.WithdrawSucceed.class, name = "withdraw-succeed"),
        @JsonSubTypes.Type(value = Account.WithdrawResult.WithdrawFailed.class, name = "withdraw-failed")
    })
    public sealed interface WithdrawResult {
        record WithdrawFailed(String errorMsg) implements Account.WithdrawResult {}
        record WithdrawSucceed() implements Account.WithdrawResult {}
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Account.DepositResult.DepositSucceed.class, name = "deposit-succeed"),
        @JsonSubTypes.Type(value = Account.DepositResult.DepositFailed.class, name = "deposit-failed")
    })
    public sealed interface DepositResult {
        record DepositFailed(String errorMsg) implements Account.DepositResult {}
        record DepositSucceed() implements Account.DepositResult {}
    }

    public record State(String id, int balance) {

        public State withdraw(int amount) {
            return new State(id, balance - amount);
        }

        public State deposit(int amount) {
            return new State(id, balance + amount);
        }

        public static State emptyState() {
            return new State("", 0);
        }

        public boolean isEmpty() {
            return id.isEmpty();
        }

    }

}
