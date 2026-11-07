package com.example;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.QueueOutput;
import com.microsoft.azure.functions.annotation.QueueTrigger;

public class QueueIdentityDemo {
    @FunctionName("QueueIdentityDemo")
    public void run(
        @QueueTrigger(
            name = "message",
            queueName = "input-queue",
            connection = "StorageConnection") String message,
        @QueueOutput(
            name = "output",
            queueName = "output-queue",
            connection = "StorageConnection") OutputBinding<String> output,
        final ExecutionContext context) {

        String result = "processed by function: " + message;
        context.getLogger().info("Input: " + message);
        output.setValue(result);
    }
}
