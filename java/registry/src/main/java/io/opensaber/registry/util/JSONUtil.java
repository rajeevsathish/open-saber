package io.opensaber.registry.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jsonldjava.core.JsonLdError;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.utils.JsonUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JSONUtil {
    private static Type stringObjMapType = new TypeToken<Map<String, Object>>() {
    }.getType();

    public static Map<String, Object> convertObjectJsonMap(Object object) {
        Gson gson = new Gson();
        String result = gson.toJson(object);
        return gson.fromJson(result, stringObjMapType);
    }
    
    public static String getStringWithReplacedText(String payload, String value, String replacement){
		Pattern pattern = Pattern.compile(value);
		Matcher matcher = pattern.matcher(payload);
		return matcher.replaceAll(replacement);
	}
    
    public static Map<String,Object> frameJsonAndRemoveIds(String regex, String json, Gson gson, String frame) throws JsonLdError, IOException{
    	Map<String, Object> reqMap = gson.fromJson(json, stringObjMapType);
    	JsonObject jsonObj = gson.fromJson(json, JsonObject.class);
    	String rootType = null;
    	if(jsonObj.get("@graph")!=null){
    		rootType = jsonObj.get("@graph").getAsJsonArray().get(0).getAsJsonObject().get("@type").getAsString();
    	}else{
    		rootType = jsonObj.get("@type").getAsString();
    	}
    	frame = frame.replace("<@type>", rootType);
    	//JsonUtils.fromString(frame)
    	JsonLdOptions options = new JsonLdOptions();
    	options.setCompactArrays(true);
    	Map<String,Object> framedJsonLD = JsonLdProcessor.frame(reqMap, JsonUtils.fromString(frame), options);
    	json = gson.toJson(framedJsonLD);
    	json = JSONUtil.getStringWithReplacedText(json, regex, StringUtils.EMPTY);
    	System.out.println(json);
    	return gson.fromJson(json, stringObjMapType);
    }

	/**
	 * Returns true if the passed in string is a valid json
	 * @param str
	 * @return
	 */
	public static boolean isJsonString(String str) {
		boolean isJson = false;
		try {
			final ObjectMapper mapper = new ObjectMapper();
			JsonNode node = mapper.readTree(str);
			// At least one key is expected
			if (node.fieldNames().hasNext()) {
				isJson = true;
			}
		} catch(java.io.IOException e) {
			isJson = false;
		}
		return isJson;
	}
}
