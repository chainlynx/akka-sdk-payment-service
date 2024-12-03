package com.example.transaction.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Transaction.Request.class, name = "request"),
    @JsonSubTypes.Type(value = Transaction.Response.Received.class, name = "received"),
    @JsonSubTypes.Type(value = Transaction.Response.Processing.class, name = "processing")
})
public sealed interface Transaction {

    record Request(String processId, String from, String to, int amount) implements Transaction {}

    sealed interface Response extends Transaction {

        record Received(String txId, String status, Long received) implements Response {}

        record Processing(String txId, String status, String message) implements Response {}

    }

}
