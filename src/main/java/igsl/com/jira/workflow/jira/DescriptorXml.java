package igsl.com.jira.workflow.jira;

import com.opensymphony.workflow.loader.*;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/** Parses descriptor fragments only. External entities and DTDs are never resolved. */
public final class DescriptorXml {
    private DescriptorXml() { }
    public static Element element(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        } catch (Exception error) { throw new IllegalArgumentException("Invalid workflow descriptor XML", error); }
    }
    public static ActionDescriptor action(ActionDescriptor source) {
        return DescriptorFactory.getFactory().createActionDescriptor(element(source.asXML()));
    }
    public static WorkflowDescriptor workflow(WorkflowDescriptor source) {
        return workflow(serialize(source));
    }
    public static WorkflowDescriptor workflow(String xml) {
        return DescriptorFactory.getFactory().createWorkflowDescriptor(element(xml));
    }
    public static String serialize(WorkflowDescriptor source) {
        // writeXML omits the declaration/DOCTYPE used by the full workflow export format.
        java.io.StringWriter xml = new java.io.StringWriter();
        source.writeXML(new java.io.PrintWriter(xml), 0);
        return xml.toString();
    }
    /** Native publication changes these three root metadata entries without changing workflow behavior. */
    public static String contentFingerprint(WorkflowDescriptor source) {
        WorkflowDescriptor copy = workflow(source);
        for (String key : java.util.List.of(com.atlassian.jira.workflow.JiraWorkflow.JIRA_META_UPDATE_AUTHOR_NAME,
                com.atlassian.jira.workflow.JiraWorkflow.JIRA_META_UPDATE_AUTHOR_KEY,
                com.atlassian.jira.workflow.JiraWorkflow.JIRA_META_UPDATED_DATE)) copy.getMetaAttributes().remove(key);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(copy.asXML().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
