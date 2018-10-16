package io.opensaber.registry.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opensaber.registry.middleware.transform.Data;
import io.opensaber.registry.middleware.transform.ErrorCode;
import io.opensaber.registry.middleware.transform.ITransformer;
import io.opensaber.registry.middleware.transform.TransformationException;
import io.opensaber.registry.middleware.util.Constants.JsonldConstants;
import io.opensaber.registry.util.JSONUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;

@Component
public class JsonToLdTransformer implements ITransformer<Object> {

	private static Logger logger = LoggerFactory.getLogger(JsonToLdTransformer.class);
	private List<String> keysToPurge = new ArrayList<>();
	private String prefix = "";
	private static final String SEPERATOR = ":";

	public Data<Object> transform(Data<Object> data) throws TransformationException {
		try {
			ObjectMapper mapper = new ObjectMapper();
			ObjectNode input = (ObjectNode) mapper.readTree(data.getData().toString());
			JsonNode jsonNode = getconstructedJson(input, keysToPurge);
			return new Data<>(jsonNode);
		} catch (Exception ex) {
			logger.error(ex.getMessage(), ex);
			throw new TransformationException(ex.getMessage(), ex, ErrorCode.JSONLD_TO_JSON_TRANFORMATION_ERROR);
		}
	}

	private JsonNode getconstructedJson(ObjectNode rootDataNode, List<String> keysToPurge)
			throws IOException, ParseException {

		setPurgeData(keysToPurge);
		ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
		for (JsonNode graphNode : rootDataNode.path(JsonldConstants.GRAPH)) {
			ObjectNode rootNode = addRootTypeNode(graphNode);
			if (keysToPurge.size() != 0)
				JSONUtil.removeNodes(rootNode, keysToPurge);// purgedKeys(rootNode);
			arrayNode.add(rootNode);
			JSONUtil.trimPrefix(rootNode, prefix);
		}
		return arrayNode;
	}

	private ObjectNode addRootTypeNode(JsonNode graphNode) {
		String rootNodeType = graphNode.path(JsonldConstants.TYPE).asText();
		setPrefix(rootNodeType.toLowerCase());
		ObjectNode rootNode = JsonNodeFactory.instance.objectNode();
		rootNode.set(rootNodeType, graphNode);
		return rootNode;

	}

	@Override
	public void setPurgeData(List<String> keyToPruge) {
		this.keysToPurge = keyToPruge;

	}

	private void setPrefix(String prefix) {
		this.prefix = prefix + SEPERATOR;
	}

}
