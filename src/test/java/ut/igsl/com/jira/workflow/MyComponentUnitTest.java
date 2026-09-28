package ut.igsl.com.jira.workflow;

import org.junit.Test;
import igsl.com.jira.workflow.api.MyPluginComponent;
import igsl.com.jira.workflow.impl.MyPluginComponentImpl;

import static org.junit.Assert.assertEquals;

public class MyComponentUnitTest {
    @Test
    public void testMyName() {
        MyPluginComponent component = new MyPluginComponentImpl(null);
        assertEquals("names do not match!", "myComponent", component.getName());
    }
}