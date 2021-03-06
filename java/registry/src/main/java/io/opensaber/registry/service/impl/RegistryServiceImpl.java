package io.opensaber.registry.service.impl;

import io.opensaber.converters.JenaRDF4J;
import io.opensaber.pojos.ComponentHealthInfo;
import io.opensaber.pojos.HealthCheckResponse;
import io.opensaber.registry.dao.RegistryDao;
import io.opensaber.registry.exception.*;
import io.opensaber.registry.middleware.util.Constants;
import io.opensaber.registry.middleware.util.RDFUtil;
import io.opensaber.registry.model.RegistrySignature;
import io.opensaber.registry.schema.config.SchemaConfigurator;
import io.opensaber.registry.service.EncryptionService;
import io.opensaber.registry.service.RegistryService;
import io.opensaber.registry.service.SignatureService;
import io.opensaber.registry.sink.DatabaseProvider;
import io.opensaber.registry.util.GraphDBFactory;
import io.opensaber.registry.util.JSONUtil;
import io.opensaber.utils.converters.RDF2Graph;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.ext.com.google.common.io.ByteStreams;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.JsonLDWriteContext;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.WriterDatasetRIOT;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.RiotLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;


@Component
public class RegistryServiceImpl implements RegistryService {

	private static Logger logger = LoggerFactory.getLogger(RegistryServiceImpl.class);

	@Autowired
	private RegistryDao registryDao;

	@Autowired
	DatabaseProvider databaseProvider;
	
	@Autowired
	EncryptionService encryptionService;

    @Autowired
    SignatureService signatureService;
    
    @Autowired
    SchemaConfigurator schemaConfigurator;

    @Value("${encryption.enabled}")
    private boolean encryptionEnabled;

    @Value("${signature.enabled}")
    private boolean signatureEnabled;

	@Value("${signature.domain}")
	private String signatureDomain;

	@Value("${signature.keysURL}")
	private String signatureKeyURl;
	
	@Value("${frame.file}")
	private String frameFile;
	
	@Value("${audit.frame.file}")
	private String auditFrameFile;
	
	@Value("${registry.context.base}")
	private String registryContextBase;
	
	@Value("${registry.system.base}")
	private String registrySystemBase;

	@Value("${registry.rootEntity.type}")
	private String registryRootEntityType;

	@Override
	public List getEntityList(){
		return registryDao.getEntityList();
	}

	@Override
	public String addEntity(Model rdfModel, String subject, String property) throws DuplicateRecordException, EntityCreationException,
	EncryptionException, AuditFailedException, MultipleEntityException, RecordNotFoundException {
		try {
			Resource root = getRootNode(rdfModel);
			String label = getRootLabel(root);	
			if(encryptionEnabled){
				encryptModel(rdfModel);
			}
			Graph graph = generateGraphFromRDF(rdfModel);

			// Append _: to the root node label to create the entity as Apache Jena removes the _: for the root node label
			// if it is a blank node
			return registryDao.addEntity(graph, label, subject, property);

		} catch (EntityCreationException | EncryptionException | AuditFailedException | DuplicateRecordException | MultipleEntityException ex) {
			throw ex;
		} catch (Exception ex) {
			logger.error("Exception when creating entity: ", ex);
			throw ex;
		}
	}

	@Override
	public boolean updateEntity(Model entity) throws RecordNotFoundException, EntityCreationException, EncryptionException, AuditFailedException, MultipleEntityException,
			SignatureException.UnreachableException, IOException, SignatureException.CreationException {
		boolean isUpdated;
		Resource root = getRootNode(entity);
		String label = getRootLabel(root);
		String rootType = getTypeForRootLabel(entity,root);
		if(rootType.equalsIgnoreCase(registryContextBase+registryRootEntityType)){
			if(encryptionEnabled){
				encryptModel(entity);
			}
			Graph graph = generateGraphFromRDF(entity);
			logger.debug("Service layer graph :", graph);
			isUpdated =  registryDao.updateEntity(graph, label, "update");
			if(signatureEnabled) {
				getEntityAndUpdateSign(entity, label);
			}
		} else {
			logger.error("Exception while updating entity");
			throw new UnsupportedOperationException("Updates to child entity not supported");
		}

		return isUpdated;
	}

	/**
	 * This method will get entity details and sign the entity and will update
	 * @param entity
	 * @param label
	 * @throws EncryptionException
	 * @throws AuditFailedException
	 * @throws RecordNotFoundException
	 * @throws EntityCreationException
	 * @throws IOException
	 * @throws MultipleEntityException
	 * @throws SignatureException.UnreachableException
	 * @throws SignatureException.CreationException
	 */
	void getEntityAndUpdateSign(Model entity,String label) throws EncryptionException, AuditFailedException, RecordNotFoundException, EntityCreationException, IOException,
			MultipleEntityException, SignatureException.UnreachableException, SignatureException.CreationException {
		final String ID_REGEX = "\"@id\"\\s*:\\s*\"[a-z]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\",";
		Map signReq  = new HashMap<String, Object>();
		RegistrySignature rs = new RegistrySignature();
		Model jenaEntityModel = getEntityById(label, true);
		//remove sign part from model
		Model signatureModel = RDFUtil.removeAndRetrieveSignature(jenaEntityModel,registryContextBase);
		String jenaJSON = frameEntity(jenaEntityModel);
		signReq.put("entity",JSONUtil.getStringWithReplacedText(jenaJSON,ID_REGEX, StringUtils.EMPTY));
		Map<String, Object> entitySignMap = (Map<String, Object>) signatureService.sign(signReq);
		entitySignMap.put("createdDate",rs.getCreatedTimestamp());
		entitySignMap.put("keyUrl",signatureKeyURl);
		Graph graph = generateGraphFromRDF(RDFUtil.getUpdatedSignedModel(entity,registryContextBase,signatureDomain,entitySignMap,signatureModel));
		registryDao.updateEntity(graph, label, "update");
	}



	/**
	 * Optionally gets signatures along with other information.
	 *
	 * @param label
	 * @param includeSignatures
	 * @return
	 * @throws RecordNotFoundException
	 * @throws EncryptionException
	 * @throws AuditFailedException
	 */
	@Override
	public Model getEntityById(String label, boolean includeSignatures) throws RecordNotFoundException, EncryptionException, AuditFailedException {
		Graph graph = registryDao.getEntityById(label, includeSignatures);
		org.eclipse.rdf4j.model.Model model = RDF2Graph.convertGraph2RDFModel(graph, label);
		Model jenaEntityModel = JenaRDF4J.asJenaModel(model);
		if(encryptionEnabled){
			decryptModel(jenaEntityModel);
		}
		logger.debug("RegistryServiceImpl : rdf4j model :", model);
		return jenaEntityModel;
	}


	public HealthCheckResponse health() throws Exception {
		HealthCheckResponse healthCheck;
		boolean databaseServiceup = databaseProvider.isDatabaseServiceUp();
        boolean overallHealthStatus = databaseServiceup;
		List<ComponentHealthInfo> checks = new ArrayList<>();

        ComponentHealthInfo databaseServiceInfo = new ComponentHealthInfo(Constants.OPENSABER_DATABASE_NAME, databaseServiceup);
        checks.add(databaseServiceInfo);

        if (encryptionEnabled) {
            boolean encryptionServiceStatusUp = encryptionService.isEncryptionServiceUp();
            ComponentHealthInfo encryptionHealthInfo = new ComponentHealthInfo(Constants.SUNBIRD_ENCRYPTION_SERVICE_NAME, encryptionServiceStatusUp);
            checks.add(encryptionHealthInfo);
            overallHealthStatus = overallHealthStatus && encryptionServiceStatusUp;
        }

        if (signatureEnabled) {
            boolean signatureServiceStatusUp = signatureService.isServiceUp();
            ComponentHealthInfo signatureServiceInfo = new ComponentHealthInfo(Constants.SUNBIRD_SIGNATURE_SERVICE_NAME, signatureServiceStatusUp);
            checks.add(signatureServiceInfo);
            overallHealthStatus = overallHealthStatus && signatureServiceStatusUp;
		}

		healthCheck = new HealthCheckResponse(Constants.OPENSABER_REGISTRY_API_NAME, overallHealthStatus, checks);
        logger.info("Heath Check :  ", checks.toArray().toString());
		return healthCheck;
	}

	@Override
	public String frameEntity(Model jenaEntityModel) throws IOException, MultipleEntityException, EntityCreationException {
		//Model jenaEntityModel = JenaRDF4J.asJenaModel(entityModel);
		Resource root = getRootNode(jenaEntityModel);
		String rootLabelType = getTypeForRootLabel(jenaEntityModel, root);
		logger.debug("RegistryServiceImpl : jenaEntityModel for framing: {} \n root : {}, \n rootLabelType: {}",jenaEntityModel,root,rootLabelType);
		DatasetGraph g = DatasetFactory.create(jenaEntityModel).asDatasetGraph();
		JsonLDWriteContext ctx = new JsonLDWriteContext();
		InputStream is = this.getClass().getClassLoader().getResourceAsStream(frameFile);
		String fileString = new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8);
		fileString = fileString.replace("<@type>", rootLabelType);
		ctx.setFrame(fileString);
		WriterDatasetRIOT w = RDFDataMgr.createDatasetWriter(org.apache.jena.riot.RDFFormat.JSONLD_FRAME_FLAT);
		PrefixMap pm = RiotLib.prefixMap(g);
		String base = null;
		StringWriter sWriterJena = new StringWriter();
		w.write(sWriterJena, g, pm, base, ctx);
		String jenaJSON = sWriterJena.toString();
		logger.debug("RegistryServiceImpl : jenaJSON for framing : {}", jenaJSON);
		return jenaJSON;
	}
	
	@Override
	public String frameSearchEntity(org.eclipse.rdf4j.model.Model entityModel) throws IOException, MultipleEntityException, EntityCreationException {
		Model jenaEntityModel = JenaRDF4J.asJenaModel(entityModel);
		String jenaJSON = "";
		if(!jenaEntityModel.isEmpty()){
			String rootLabelType = getTypeForSearch(jenaEntityModel);
			logger.debug("RegistryServiceImpl : jenaEntityModel for framing: {} \n root : {}, \n rootLabelType: {}",jenaEntityModel,rootLabelType);
			DatasetGraph g = DatasetFactory.create(jenaEntityModel).asDatasetGraph();
			JsonLDWriteContext ctx = new JsonLDWriteContext();
			InputStream is = this.getClass().getClassLoader().getResourceAsStream(frameFile);
			String fileString = new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8);
			fileString = fileString.replace("<@type>", rootLabelType);
			ctx.setFrame(fileString);
			WriterDatasetRIOT w = RDFDataMgr.createDatasetWriter(org.apache.jena.riot.RDFFormat.JSONLD_FRAME_FLAT);
			PrefixMap pm = RiotLib.prefixMap(g);
			String base = null;
			StringWriter sWriterJena = new StringWriter();
			w.write(sWriterJena, g, pm, base, ctx);
			jenaJSON = sWriterJena.toString();
		}
		logger.debug("RegistryServiceImpl : jenaJSON for framing : {}", jenaJSON);
		return jenaJSON;
	}
	
	@Override
	public String frameAuditEntity(org.eclipse.rdf4j.model.Model entityModel) throws IOException {
		Model jenaEntityModel = JenaRDF4J.asJenaModel(entityModel);
		logger.debug("RegistryServiceImpl : jenaEntityModel for audit-framing: {} ",jenaEntityModel);
		DatasetGraph g = DatasetFactory.create(jenaEntityModel).asDatasetGraph();
		JsonLDWriteContext ctx = new JsonLDWriteContext();
		InputStream is = this.getClass().getClassLoader().getResourceAsStream(auditFrameFile);
		String fileString = new String(ByteStreams.toByteArray(is), StandardCharsets.UTF_8);
		ctx.setFrame(fileString);
		WriterDatasetRIOT w = RDFDataMgr.createDatasetWriter(org.apache.jena.riot.RDFFormat.JSONLD_FRAME_FLAT);
		PrefixMap pm = RiotLib.prefixMap(g);
		String base = null;
		StringWriter sWriterJena = new StringWriter();
		w.write(sWriterJena, g, pm, base, ctx);
		String jenaJSON = sWriterJena.toString();
		logger.debug("RegistryServiceImpl : jenaJSON for audit-framing: {}", jenaJSON);
		return jenaJSON;
	}
	
	@Override
	public org.eclipse.rdf4j.model.Model getAuditNode(String id) throws IOException, NoSuchElementException, RecordNotFoundException,
			EncryptionException, AuditFailedException {
		String label = id + "-AUDIT";
        Graph graph = registryDao.getEntityById(label, false);
		org.eclipse.rdf4j.model.Model model = RDF2Graph.convertGraph2RDFModel(graph, label);
		logger.debug("RegistryServiceImpl : Audit Model : " + model);
		return model;
	}

	@Override
	public boolean deleteEntityById(String id) throws AuditFailedException, RecordNotFoundException {
		boolean isDeleted = registryDao.deleteEntityById(id);
		if(!isDeleted){
			throw new UnsupportedOperationException(Constants.DELETE_UNSUPPORTED_OPERATION_ON_ENTITY);
		}
		return isDeleted;
	}

	private Graph generateGraphFromRDF(Model entity) throws EntityCreationException, MultipleEntityException{
		Graph graph = GraphDBFactory.getEmptyGraph();
		StmtIterator iterator = entity.listStatements();
		while (iterator.hasNext()) {
			Statement rdfStatement = iterator.nextStatement();
			org.eclipse.rdf4j.model.Statement rdf4jStatement = JenaRDF4J.asrdf4jStatement(rdfStatement);
            graph = RDF2Graph.convertRDFStatement2Graph(rdf4jStatement, graph, registryContextBase);
		}
		return graph;
	}
	
	private String getRootLabel(Resource subject) {
		String label = subject.toString();
		if (subject.isAnon() && subject.getURI() == null) {
			label = String.format("_:%s", label);
		}
		return label;
	}
	
	private Resource getRootNode(Model entity) throws EntityCreationException, MultipleEntityException{
		List<Resource> rootLabels = RDFUtil.getRootLabels(entity);
		if (rootLabels.size() == 0) {
			throw new EntityCreationException(Constants.NO_ENTITY_AVAILABLE_MESSAGE);
		} else if (rootLabels.size() > 1) {
			throw new MultipleEntityException(Constants.ADD_UPDATE_MULTIPLE_ENTITIES_MESSAGE);
		} else {
			return rootLabels.get(0);
		}
	}
	private String getTypeForRootLabel(Model entity, Resource root) throws EntityCreationException, MultipleEntityException{
		List<String> rootLabelType = RDFUtil.getTypeForSubject(entity, root);
		if (rootLabelType.size() == 0) {
			throw new EntityCreationException(Constants.NO_ENTITY_AVAILABLE_MESSAGE);
		} else if (rootLabelType.size() > 1) {
			throw new MultipleEntityException(Constants.ADD_UPDATE_MULTIPLE_ENTITIES_MESSAGE);
		} else {
			return rootLabelType.get(0);
		}
	}
	
	
	private String getTypeForSearch(Model entity) throws EntityCreationException, MultipleEntityException{
		List<Resource> rootLabels = RDFUtil.getRootLabels(entity);
		if (rootLabels.size() == 0) {
			throw new EntityCreationException(Constants.NO_ENTITY_AVAILABLE_MESSAGE);
		} else {
			return getTypeForRootLabel(entity, rootLabels.get(0));
		}
	}
	
	private void encryptModel(Model rdfModel) throws EncryptionException {
		setModelWithEncryptedOrDecryptedAttributes(rdfModel, true);
	}
	
	private void decryptModel(Model rdfModel) throws EncryptionException{
		setModelWithEncryptedOrDecryptedAttributes(rdfModel, false);
	}
	
	private void setModelWithEncryptedOrDecryptedAttributes(Model rdfModel, boolean isEncryptionRequired) throws EncryptionException{
		NodeIterator nodeIter = schemaConfigurator.getAllPrivateProperties();
		Map<Resource,Map<String,Object>> toBeEncryptedOrDecryptedAttributes = new HashMap<Resource,Map<String,Object>>();
		TypeMapper tm = TypeMapper.getInstance();
		while(nodeIter.hasNext()){
			RDFNode node = nodeIter.next();
			String predicateStr = node.toString();
			Property predicate = null;
			if(!isEncryptionRequired){
				String tailOfPredicateStr = predicateStr.substring(predicateStr.lastIndexOf("/") + 1).trim();
				predicateStr = predicateStr.replace(tailOfPredicateStr, "encrypted" + tailOfPredicateStr);
			}
			predicate = ResourceFactory.createProperty(predicateStr);
			StmtIterator stmtIter = rdfModel.listStatements(null, predicate, (RDFNode)null);
			while(stmtIter.hasNext()){
				Statement s = stmtIter.next();
				Map<String,Object> propertyMap = new HashMap<String,Object>();
				if(toBeEncryptedOrDecryptedAttributes.containsKey(s.getSubject())){
					propertyMap = toBeEncryptedOrDecryptedAttributes.get(s.getSubject());
				}
				if(propertyMap.containsKey(predicateStr)){
					Object value = propertyMap.get(predicateStr);
					List valueList = new ArrayList();
					if(value instanceof List){
						valueList = (List)value;
					}
					valueList.add(s.getObject().asLiteral().getLexicalForm());
				}
				propertyMap.put(predicateStr,s.getObject().asLiteral().getLexicalForm());
				toBeEncryptedOrDecryptedAttributes.put(s.getSubject(), propertyMap);
			}
		}
		for(Map.Entry<Resource,Map<String,Object>> entry: toBeEncryptedOrDecryptedAttributes.entrySet()){
			Map<String, Object> listPropertyMap = new HashMap<String, Object>();
			Map<String, Object> propertyMap = entry.getValue();
			entry.getValue().forEach((k, v) -> {
				if (v instanceof List) {
					listPropertyMap.put(k, v);
				}
			});
			listPropertyMap.forEach((k, v) -> propertyMap.remove(k));
			Map<String,Object> encAttributes = new HashMap<String, Object>();
			if(isEncryptionRequired){
				encAttributes = encryptionService.encrypt(propertyMap);
			}else{
				encAttributes = encryptionService.decrypt(propertyMap);
			}
			for(Map.Entry<String, Object> listEntry: listPropertyMap.entrySet()){
				Resource k = entry.getKey();
				Object v = entry.getValue();
				List values = (List)v;
				List encValues = new ArrayList();
				String encDecValue = null;
				for(Object listV : values){
					if(isEncryptionRequired){
						encDecValue = encryptionService.encrypt(listV);
					}else{
						encDecValue = encryptionService.decrypt(listV);
					}
					encValues.add(encDecValue);
				}
				listEntry.setValue(encValues);
			}
			encAttributes.putAll(listPropertyMap);
			Resource encSubject = entry.getKey();
			for(Map.Entry<String,Object> propEntry: encAttributes.entrySet()){
				Property predicate = ResourceFactory.createProperty(propEntry.getKey());
				StmtIterator stmtIter = rdfModel.listStatements(entry.getKey(), predicate, (RDFNode)null);
				List<Statement> stmtList = stmtIter.toList();
				if(stmtList.size()>0){
					String datatype = stmtList.get(0).getObject().asLiteral().getDatatypeURI();
					RDFDatatype rdt = tm.getSafeTypeByName(datatype);
					rdfModel.remove(stmtList);
					String predicateStr = predicate.toString();
					String tailOfPredicateStr = predicateStr.substring(predicateStr.lastIndexOf("/") + 1).trim();
					if(isEncryptionRequired){
						predicateStr = predicateStr.replace(tailOfPredicateStr, "encrypted" + tailOfPredicateStr);
					}else{
						if(schemaConfigurator.isEncrypted(tailOfPredicateStr)){
							predicateStr = predicateStr.replace(tailOfPredicateStr, tailOfPredicateStr.substring(9));
						}
					}
					Property encPredicate = ResourceFactory.createProperty(predicateStr);
					if(propEntry.getValue() instanceof List){
						List values = (List)propEntry.getValue();
						for(Object value: values){
							Literal literal = ResourceFactory.createTypedLiteral((String)value, rdt);
							rdfModel.add(encSubject, encPredicate, literal);
						}
					}else{
						Literal literal = ResourceFactory.createTypedLiteral((String)propEntry.getValue(), rdt);
						rdfModel.add(encSubject, encPredicate, literal);
					}
				}
			}
		}
	}
}
