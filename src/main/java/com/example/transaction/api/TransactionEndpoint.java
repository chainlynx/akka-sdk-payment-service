package com.example.transaction.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.example.transaction.application.TransactionLogger;
import com.example.transaction.domain.Transaction;
import com.example.transaction.application.TransactionWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;

@HttpEndpoint("/transaction")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class TransactionEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TransactionEndpoint.class);

    private final ComponentClient client;

    public TransactionEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Get("/summary/{processId}")
    public CompletionStage<TransactionLogger.State> getTransactionSummary(String processId) {
        log.info("Get process overview with id [{}].", processId);
        return client
            .forKeyValueEntity(processId)
            .method(TransactionLogger::get)
            .invokeAsync()
            .thenApply(summary -> summary);
    }

    @Get("/{txId}")
    public CompletionStage<TransactionWorkflow.State> getTransaction(String txId) {
        log.info("Get transaction with id [{}].", txId);
        return client
            .forWorkflow(txId)
            .method(TransactionWorkflow::get)
            .invokeAsync()
            .thenApply(state -> state);
    }

    @Post("/{txId}/process")
    public CompletionStage<Transaction.Response> process(String txId, Transaction.Request request) {
        log.info("Process transaction with id [{}].", txId);
        return client
            .forWorkflow(txId)
            .method(TransactionWorkflow::process)
            .invokeAsync(request)
            .thenApply(response -> response);
    }

}
