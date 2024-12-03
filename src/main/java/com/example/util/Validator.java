package com.example.util;

import akka.javasdk.client.ComponentMethodRef;
import akka.javasdk.client.ComponentMethodRef1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

public class Validator {

    private static final Logger log = LoggerFactory.getLogger(Validator.class);

    public static ValidationBuilder start(){
        return new ValidationBuilder(List.of());
    }

    public static ValidationBuilder validate(Validation... validations){
        return new ValidationBuilder(validations);
    }

    public record ValidationBuilder(
        List<Validation> validations,
        List<ServiceValidation> serviceValidations,
        List<ServiceNotification> serviceNotifications,
        List<String> reasons,
        Mode mode
    ) {

        public ValidationBuilder(List<Validation> validations){
            this(validations, List.of(), List.of(), List.of(), Mode.PASSIVE);
        }

        public ValidationBuilder(Validation[] validations){
            this(Arrays.stream(validations).toList(), List.of(), List.of(), List.of(), Mode.PASSIVE);
        }

        public ValidationBuilder validate(Validation... validationsIn){
            var current = new ArrayList<>(validations);
            current.addAll(Arrays.stream(validationsIn).toList());
            return new ValidationBuilder(current, serviceValidations, serviceNotifications, reasons, mode);
        }

        public ValidationBuilder validateFields(String field, Map<String, String> fields, Function<String, Validation> validation){
            if(fields.containsKey(field)) {
                var current = new ArrayList<>(validations);
                current.add(validation.apply(fields.get(field)));
                return new ValidationBuilder(current, serviceValidations, serviceNotifications, reasons, mode);
            }
            return this;
        }

        public ValidationBuilder mode(Mode mode){
            return new ValidationBuilder(validations, serviceValidations, serviceNotifications, reasons, mode);
        }

        public ValidationBuilder resolve(ServiceValidation... serviceValidationsIn){
            var current = new ArrayList<>(serviceValidations);
            current.addAll(Arrays.stream(serviceValidationsIn).toList());
            return new ValidationBuilder(validations, current, serviceNotifications, reasons, mode);
        }

        public ValidationBuilder notify(ServiceNotification... serviceNotificationsIn){
            var current = new ArrayList<>(serviceNotifications);
            current.addAll(Arrays.stream(serviceNotificationsIn).toList());
            return new ValidationBuilder(validations, serviceValidations, current, reasons, mode);
        }

        public ValidationBuilder resolveIfExists(String field, Map<String, String> fields, Function<String, ServiceValidation> serviceValidation){
            if(fields.containsKey(field)) {
                var current = new ArrayList<>(serviceValidations);
                current.add(serviceValidation.apply(fields.get(field)));
                return new ValidationBuilder(validations, current, serviceNotifications, reasons, mode);
            }
            return this;
        }

        public <T> CompletionStage<T> handleAsync(BiFunction<Result, String, T> func) {
            // Nothing to validate
            if (validations.isEmpty() && serviceValidations.isEmpty()) {
                return CompletableFuture.completedFuture(func.apply(Result.SUCCESS, ""));
            }

            // First handle synchronous validations
            var results = new ArrayList<String>();
            boolean hasFailedSync = false;

            for (Validation validation : validations) {
                if (validation.result()) {
                    results.add(validation.message());
                    if (mode == Mode.FAIL_FAST) {
                        hasFailedSync = true;
                        break;
                    }
                }
            }

            // If we have a fail-fast failure or no service validations, return immediately
            if ((mode == Mode.FAIL_FAST && hasFailedSync) || serviceValidations.isEmpty()) {
                return CompletableFuture.completedFuture(
                    func.apply(
                        results.isEmpty() ? Result.SUCCESS : Result.ERROR,
                        results.stream().reduce("", "%s\n%s"::formatted)
                    )
                );
            }

            // Handle async service validations based on mode
            return processServiceValidationsAsync(results)
                .thenApply(finalResults ->
                    func.apply(
                        finalResults.isEmpty() ? Result.SUCCESS : Result.ERROR,
                        finalResults.stream().reduce("", "%s\n%s"::formatted)
                    )
                );
        }

        public <T> T handle(BiFunction<Result, String, T> func) {
            // Nothing to validate
            if (validations.isEmpty() && serviceValidations.isEmpty()) {
                return func.apply(Result.SUCCESS, "");
            }

            // Handle synchronous validations
            var results = new ArrayList<String>();
            boolean hasFailedSync = false;

            for (Validation validation : validations) {
                if (validation.result()) {
                    results.add(validation.message());
                    if (mode == Mode.FAIL_FAST) {
                        hasFailedSync = true;
                        break;
                    }
                }
            }

            // If we have no service validations or we're in fail-fast mode and already failed
            if (serviceValidations.isEmpty() || (mode == Mode.FAIL_FAST && hasFailedSync)) {
                return func.apply(
                    results.isEmpty() ? Result.SUCCESS : Result.ERROR,
                    results.stream().reduce("", "%s\n%s"::formatted)
                );
            }

            // For service validations, we need to block and wait for all results
            try {

                List<String> serviceResults = switch (mode) {
                    case FAIL_FAST -> processServiceValidationsSequentially(results);
                    case PASSIVE -> processServiceValidationsParallel(results);
                };

                return func.apply(
                    serviceResults.isEmpty() ? Result.SUCCESS : Result.ERROR,
                    serviceResults.stream().reduce("", "%s\n%s"::formatted)
                );

            } catch (Exception e) {
                log.error("Service validation failed", e);
                results.add("Service validation failed: " + e.getMessage());
                return func.apply(Result.ERROR,
                    results.stream().reduce("", "%s\n%s"::formatted));
            }
        }

        private List<String> processServiceValidationsSequentially(List<String> initialResults) {
            var results = new ArrayList<>(initialResults);

            for (ServiceValidation validation : serviceValidations) {
                try {
                    boolean failed = validation.resultAsync().toCompletableFuture().join();
                    if (failed) {
                        results.add(validation.message());
                        if (mode == Mode.FAIL_FAST) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.error("Service validation failed", e);
                    results.add("Service validation failed: " + e.getMessage());
                    if (mode == Mode.FAIL_FAST) {
                        break;
                    }
                }
            }

            return results;
        }

        private List<String> processServiceValidationsParallel(List<String> initialResults) {
            var results = new ArrayList<>(initialResults);

            var futures = serviceValidations.stream()
                .map(validation -> validation.resultAsync()
                    .thenApply(failed -> failed ? validation.message() : null)
                    .exceptionally(ex -> {
                        log.error("Service validation failed", ex);
                        return "Service validation failed: " + ex.getMessage();
                    }))
                .map(CompletionStage::toCompletableFuture)
                .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .forEach(results::add);

            return results;
        }

        private CompletionStage<List<String>> processServiceValidationsAsync(List<String> initialResults) {
            return (mode == Mode.FAIL_FAST)
                ? processSequentially(initialResults, serviceValidations)
                : processParallel(initialResults);
        }

        private CompletionStage<List<String>> processSequentially(List<String> results, List<ServiceValidation> remaining) {
            if (remaining.isEmpty()) {
                return CompletableFuture.completedFuture(results);
            }

            ServiceValidation currentValidation = remaining.getFirst();
            List<ServiceValidation> nextValidations = remaining.subList(1, remaining.size());

            return currentValidation.resultAsync()
                .thenCompose(failed -> {
                    if (failed) {
                        results.add(currentValidation.message());
                        return CompletableFuture.completedFuture(results);
                    }
                    return processSequentially(results, nextValidations);
                })
                .exceptionally(ex -> {
                    log.error("Service validation failed", ex);
                    results.add("Service validation failed: " + ex.getMessage());
                    return results;
                });
        }

        private CompletionStage<List<String>> processParallel(List<String> initialResults) {
            List<CompletionStage<String>> validationStages = serviceValidations.stream()
                .map(validation ->
                    validation.resultAsync()
                        .thenApply(failed -> failed ? validation.message() : null)
                        .exceptionally(ex -> {
                            log.error("Service validation failed", ex);
                            return "Service validation failed: " + ex.getMessage();
                        })
                )
                .toList();

            CompletionStage<List<String>> results = CompletableFuture.completedFuture(
                new ArrayList<>(initialResults)
            );

            for (CompletionStage<String> stage : validationStages) {
                results = results.thenCombine(stage, (list, result) -> {
                    if (result != null) {
                        list.add(result);
                    }
                    return list;
                });
            }

            return results;
        }

    }

    public sealed interface Validation {
        boolean result();
        String message();
    }

    public record StringValidation(Function<String, Boolean> func, String test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record BooleanValidation(Function<Boolean, Boolean> func, boolean test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record ObjectValidation(Function<Object, Boolean> func, Object test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record IntegerValidation(Function<Integer, Boolean> func, Integer test, String message) implements Validation {
        @Override public boolean result() { return func.apply(test); }
        @Override public String message() { return message; }
    }

    public record BiIntegerValidation(BiFunction<Integer, Integer, Boolean> func, Integer i1, Integer i2, String message) implements Validation {
        @Override public boolean result() { return func.apply(i1, i2); }
        @Override public String message() { return message; }
    }

    public record TriLongValidation(TriFunction<Long, Long, Long, Boolean> func, Long p1, Long p2, Long p3, String message) implements Validation {
        @Override public boolean result() { return func.apply(p1, p2, p3); }
        @Override public String message() { return message; }
    }

    public static Validation isEmpty(String test, String reason){
        return new StringValidation(String::isBlank, test, reason);
    }

    public static Validation isNotEmpty(String test, String reason){
        return new StringValidation(t -> !t.isBlank(), test, reason);
    }

    public static Validation isTrue(boolean test, String reason){
        return new BooleanValidation(b -> b, test, reason);
    }

    public static Validation isFalse(boolean test, String reason){
        return new BooleanValidation(b -> !b, test, reason);
    }

    public static Validation isNull(Object test, String reason){
        return new ObjectValidation(Objects::isNull, test, reason);
    }

    public static Validation isNotNull(Object test, String reason){
        return new ObjectValidation(Objects::nonNull, test, reason);
    }

    public static Validation isLtEqZero(int test, String reason){
        return new IntegerValidation(i -> i <= 0, test, reason);
    }

    public static Validation isLtZero(int test, String reason){
        return new IntegerValidation(i -> i < 0, test, reason);
    }

    public static Validation isGtLimit(int test, int limit, String reason){
        return new BiIntegerValidation((i1, i2) -> i1 > i2, test, limit, reason);
    }

    public static Validation isFutureDate(Long dateTestMillis, Long thresholdMillis, Long baselineMillis, String reason){
        return new TriLongValidation(
            (t1, t2, t3) -> t1 + t2 > t3,
            dateTestMillis,  //t1
            thresholdMillis, //t2
            baselineMillis,  //t3
            reason
        );
    }

    public static Validation isPastDate(Long dateTestMillis, Long thresholdMillis, Long baselineMillis, String reason){
        return new TriLongValidation(
            (t1, t2, t3) -> t1 + t2 < t3,
            dateTestMillis,  //t1
            thresholdMillis, //t2
            baselineMillis,  //t3
            reason
        );
    }

    public sealed interface ServiceNotification {
        CompletionStage<Boolean> resultAsync();
        String message();
    }

    public record BooleanServiceNotification(ComponentMethodRef<?> method, String reason) implements ServiceNotification {

        @Override
        public CompletionStage<Boolean> resultAsync() {
            return method.invokeAsync()
                .handle((result, ex) -> {
                    if (ex != null) {
                        log.error("Service notification failed: {}", ex.getMessage());
                        return true; //true, there is an error
                    }
                    return result == null; //a null result means the expected result wasn't met
                });
        }

        @Override
        public String message() {
            return reason;
        }

    }

    public record BooleanServiceNotification1<A1, R extends Boolean>(ComponentMethodRef1<A1, R> method, A1 p1, String reason) implements ServiceNotification {

        @Override
        public CompletionStage<Boolean> resultAsync() {
            return method.invokeAsync(p1)
                .handle((result, ex) -> {
                    if (ex != null) {
                        log.error("Service notification failed: {}", ex.getMessage());
                        return true;
                    }
                    return !result.booleanValue(); //result == true here means verification passed
                });
        }

        @Override
        public String message() {
            return reason;
        }

    }

    public sealed interface ServiceValidation {
        CompletionStage<Boolean> resultAsync();
        String message();
    }

    public record BooleanServiceValidation(ComponentMethodRef<?> method, String reason) implements ServiceValidation {

        @Override
        public CompletionStage<Boolean> resultAsync() {
            return method.invokeAsync()
                .handle((result, ex) -> {
                    if (ex != null) {
                        log.error("Service validation failed: {}", ex.getMessage());
                        return true; //true, there is an error
                    }
                    return result == null; //a null result means the expected result wasn't met
                });
        }

        @Override
        public String message() {
            return reason;
        }

    }

    public record BooleanServiceVerification<A1, R extends Boolean>(ComponentMethodRef1<A1, R> method, A1 p1, String reason) implements ServiceValidation {

        @Override
        public CompletionStage<Boolean> resultAsync() {
            return method.invokeAsync(p1)
                .handle((result, ex) -> {
                    if (ex != null) {
                        log.error("Service validation failed: {}", ex.getMessage());
                        return true;
                    }
                    return !result.booleanValue(); //result == true here means verification passed
                });
        }

        @Override
        public String message() {
            return reason;
        }

    }

    public static ServiceValidation entityExists(ComponentMethodRef<?> method, String reason){
        return new BooleanServiceValidation(method, reason);
    }

    public static <A1, R extends Boolean> ServiceValidation verify(ComponentMethodRef1<A1, R> method, A1 p1, String reason){
        return new BooleanServiceVerification<>(method, p1, reason);
    }

    public enum Mode {
        FAIL_FAST,     //Execute validations until first failure
        PASSIVE        //Execute all validations, accumulate results
    }

    public enum Result {
        SUCCESS, ERROR
    }

    @FunctionalInterface
    interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

}
