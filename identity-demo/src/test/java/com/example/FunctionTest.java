package com.example;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class FunctionTest {
    @Test
    public void testQueueIdentityDemo() {
        OutputBinding<String> output = mock(OutputBinding.class);
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.getLogger()).thenReturn(Logger.getLogger("QueueIdentityDemoTest"));

        new QueueIdentityDemo().run(
            "hello",
            output,
            context
        );

        verify(output).setValue("processed by function: hello");
    }
}
