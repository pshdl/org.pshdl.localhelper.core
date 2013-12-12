package org.pshdl.localhelper;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.*;

public class JSONHelper {
	public static ObjectMapper getMapper() {
		final ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		// mapper.setSerializationInclusion(Include.NON_DEFAULT);
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		mapper.configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false);
		mapper.setVisibilityChecker(mapper.getSerializationConfig().getDefaultVisibilityChecker().withFieldVisibility(JsonAutoDetect.Visibility.NONE)
				.withGetterVisibility(JsonAutoDetect.Visibility.NONE).withSetterVisibility(JsonAutoDetect.Visibility.NONE).withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
		return mapper;
	}

	public static ObjectWriter getWriter() {
		return getMapper().writerWithDefaultPrettyPrinter();
	}

	public static ObjectReader getReader(Class<?> clazz) {
		return getMapper().reader(clazz);
	}
}
