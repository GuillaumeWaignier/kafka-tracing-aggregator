package org.ianitrix.kafka.pojo;

import lombok.*;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;
import org.testcontainers.shaded.com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.testcontainers.shaded.com.fasterxml.jackson.annotation.JsonInclude;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Hit {
    private String _index;
    private TracingValue _source;
}
