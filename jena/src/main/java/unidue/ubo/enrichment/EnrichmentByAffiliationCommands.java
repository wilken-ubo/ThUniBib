package unidue.ubo.enrichment;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.mycore.access.MCRAccessException;
import org.mycore.common.MCRConstants;
import org.mycore.common.MCRException;
import org.mycore.datamodel.metadata.MCRMetadataManager;
import org.mycore.datamodel.metadata.MCRObject;
import org.mycore.datamodel.metadata.MCRObjectID;
import org.mycore.frontend.cli.MCRAbstractCommands;
import org.mycore.frontend.cli.annotation.MCRCommand;
import org.mycore.frontend.cli.annotation.MCRCommandGroup;
import org.mycore.mods.MCRMODSWrapper;
import org.mycore.mods.enrichment.MCREnrichmentResolver;
import org.xml.sax.InputSource;
import org.jdom2.xpath.XPathFactory;

import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;


@MCRCommandGroup(name = "experimental DBT import commands (by affiliation gnd)")
public class EnrichmentByAffiliationCommands extends MCRAbstractCommands {

    public static final Namespace MODS_NAMESPACE = Namespace.getNamespace("mods",
            "http://www.loc.gov/mods/v3");
    public static final Namespace NAMESPACE_ZS = Namespace.getNamespace("zs",
            "http://www.loc.gov/zing/srw/");

    private static final Logger LOGGER = LogManager.getLogger(EnrichmentByAffiliationCommands.class);

    private static final String projectID = "ubo";

    @MCRCommand(syntax = "test import by affiliation with GND {0} and status {1}",
            help = "test import of publications by affiliation GND, imported documents get status one of 'confirmed', " +
                    "'submitted', 'imported'",
            order = 10
    )
    public static void testImportByAffiliation(String affiliationGND, String import_status) {
        final List<String> allowedStatus = Arrays.asList(new String[]{"confirmed", "submitted", "imported"});

        if(allowedStatus.contains(import_status)) {

            List<String> affiliatedPersonsGNDs = getAllGNDsOfAffiliatedPersons(affiliationGND);
            for(String affiliatedPersonGND : affiliatedPersonsGNDs) {
                List<String> recordIdentifiers = getAllRecordIdentifiersOfPublicationsOfPersonsByGND(affiliatedPersonGND);
                for(String ppnID : recordIdentifiers) {
                    MCRMODSWrapper wrappedMods = createMinimalMods(ppnID);
                    new MCREnrichmentResolver().enrichPublication(wrappedMods.getMODS(), "import");
                    LOGGER.debug(new XMLOutputter(Format.getPrettyFormat()).outputString(wrappedMods.getMCRObject().createXML()));
                    createOrUpdate(wrappedMods, import_status);
                }
            }

        } else {
            LOGGER.info("Status not allowed: {}, use one of {}", import_status, String.join(", ", allowedStatus));
        }

    }

    private static List<String> getAllRecordIdentifiersOfPublicationsOfPersonsByGND(String gnd) {
        List<String> recordIdentifiers = new ArrayList<>();

        String targetURL = "http://sru.k10plus.de/gvk?version=1.1&operation=searchRetrieve&query=pica.nid%3D" +
                gnd + "&maximumRecords=10&recordSchema=mods36";
        String requestContent = makeRequest(targetURL);

        SAXBuilder builder = new SAXBuilder();
        try {
            Document searchResponseXML = builder.build(new InputSource(new StringReader(requestContent)));

            XPathFactory xFactory = XPathFactory.instance();

            XPathExpression<Element> modsRecordExpr = xFactory.compile("//mods:recordIdentifier",
                    Filters.element(), null, NAMESPACE_ZS, MODS_NAMESPACE);
            List<Element> recordIdentifierElements = modsRecordExpr.evaluate(searchResponseXML);
            for(Element recordIdentifierElement : recordIdentifierElements) {
                recordIdentifiers.add(recordIdentifierElement.getValue());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        LOGGER.info("Got {} recordIdentifiers of publications for GND: {}", recordIdentifiers.size(), gnd);
        return recordIdentifiers;
    }

    private static MCRMODSWrapper createMinimalMods(String ppnID) {
        LOGGER.debug("Start createMinimalMods");

        final Element modsRoot = new Element("mods", MCRConstants.MODS_NAMESPACE);
        final Element identifierElement = new Element("identifier", MCRConstants.MODS_NAMESPACE);
        identifierElement.setAttribute("type", "ppn");
        identifierElement.setText(ppnID);

        final MCRObject ppn;
        ppn = MCRMODSWrapper.wrapMODSDocument(modsRoot, projectID);
        ppn.setId(nextFreeID());

        final MCRMODSWrapper wrapper = new MCRMODSWrapper(ppn);
        wrapper.addElement(identifierElement);

        LOGGER.debug(new XMLOutputter(Format.getPrettyFormat()).outputString(wrapper.getMCRObject().createXML()));
        LOGGER.debug("End createMinimalMods");
        return wrapper;
    }

    private static String makeRequest(String targetURL) {
        String content = "";
        try {
            URL url = new URL(targetURL);
            URLConnection request = url.openConnection();
            request.connect();
            content = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(0);
        }
        return content;
    }

    private static List<String> getAllGNDsOfAffiliatedPersons(String affiliationGND) {
        List<String> affiliatedPersonsGNDs = new ArrayList<>();

        String targetURL = "http://lobid.org/gnd/search?q=" + affiliationGND +
                "&filter=type%3APerson&format=json&size=10000";
        String requestContent = makeRequest(targetURL);

        Gson gson = new Gson();
        JsonElement jsonElement = gson.fromJson(requestContent, JsonElement.class);
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        LOGGER.info("Received information of {} affiliated persons for affiliation GND: {}",
                jsonObject.get("totalItems"), affiliationGND);
        for(JsonElement element : jsonObject.getAsJsonArray("member")) {
            JsonObject member = element.getAsJsonObject();
            String gndIdentifier = member.get("gndIdentifier").getAsString();
            affiliatedPersonsGNDs.add(gndIdentifier);
        }
        LOGGER.debug("Got the following GNDs of affiliated persons with affiliation GND {}: ", affiliatedPersonsGNDs);
        return affiliatedPersonsGNDs;
    }

    private static MCRObjectID nextFreeID() {
        return MCRObjectID.getNextFreeId(projectID, "mods");
    }

    private static void createOrUpdate(MCRMODSWrapper wrappedMCRobj, String import_status) {

        MCRObject object = wrappedMCRobj.getMCRObject();
        // save object
        try {
            setState(wrappedMCRobj, import_status);

            final Optional<MCRObjectID> alreadyExists = Optional.of(object.getId())
                    .filter(MCRMetadataManager::exists);
            if (!alreadyExists.isPresent()) {
                LOGGER.info("Create object {}!", object.getId().toString());
                MCRMetadataManager.create(object);
            } else {
                LOGGER.info("Update object {}!", object.getId().toString());
                MCRMetadataManager.update(object);
            }
        } catch (MCRAccessException e) {
            throw new MCRException("Error while creating " + object.getId().toString(), e);
        }
    }

    private static void setState(MCRMODSWrapper wrapped, String import_status) {
        wrapped.setServiceFlag("status", import_status);
    }
}