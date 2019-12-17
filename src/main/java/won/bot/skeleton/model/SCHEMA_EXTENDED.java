package won.bot.skeleton.model;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import won.protocol.vocabulary.SCHEMA;

public class SCHEMA_EXTENDED extends SCHEMA {

    public static final Property ID;
    private static Model m = ModelFactory.createDefaultModel();

    static {
        ID = m.createProperty(getURI(), "identifier");
    }
}