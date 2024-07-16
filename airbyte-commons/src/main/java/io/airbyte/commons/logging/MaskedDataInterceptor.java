/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.logging;

import static io.airbyte.commons.constants.AirbyteCatalogConstants.LOCAL_SECRETS_MASKS_PATH;

import com.fasterxml.jackson.core.type.TypeReference;
import io.airbyte.commons.constants.AirbyteSecretConstants;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.yaml.Yamls;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Custom Log4j2 {@link RewritePolicy} used to intercept all log messages and mask any JSON
 * properties in the message that match the list of maskable properties.
 * <p>
 * The maskable properties file is generated by a Gradle task in the
 * {@code :oss:airbyte-config:specs} project. The file is named {@code specs_secrets_mask.yaml} and
 * is located in the {@code src/main/resources/seed} directory of the
 * {@code :oss:airbyte-config:init} project.
 */
@Plugin(name = "MaskedDataInterceptor",
        category = "Core",
        elementType = "rewritePolicy",
        printObject = true)
public class MaskedDataInterceptor implements RewritePolicy {

  protected static final Logger logger = StatusLogger.getLogger();

  /**
   * Regular expression pattern flag that enables case in-sensitive matching.
   */
  private static final String CASE_INSENSITIVE_FLAG = "(?i)";

  // This is a little circuitous, but it gets the regex syntax highlighting in intelliJ to work.
  private static final String DESTINATION_ERROR_PREFIX = Pattern.compile("^(?<destinationPrefix>.*destination.*\\s+>\\s+ERROR.+)").pattern();

  /**
   * Regular expression replacement pattern for applying the mask to PII log messages.
   */
  private static final String KNOWN_PII_LOG_MESSAGE_REPLACEMENT_PATTERN =
      "${destinationPrefix}${messagePrefix}" + AirbyteSecretConstants.SECRETS_MASK;

  /**
   * Delimiter used as part of the regular expression pattern for applying the mask to property
   * values.
   */
  private static final String PROPERTY_MATCHING_PATTERN_DELIMITER = "|";

  /**
   * Regular expression pattern prefix for applying the mask to property values.
   */
  private static final String PROPERTY_MATCHING_PATTERN_PREFIX = "\"(";

  /**
   * Regular expression pattern suffix for applying the mask to property values.
   */
  private static final String PROPERTY_MATCHING_PATTERN_SUFFIX = ")\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^]\\[]*]|\\d+)";

  /**
   * Name of the key in the mask YAML file that contains the list of maskable properties.
   */
  private static final String PROPERTIES_KEY = "properties";

  /**
   * Regular expression pattern used to replace a key/value property with a masked value while
   * maintaining the property key/name.
   */
  private static final String REPLACEMENT_PATTERN = "\"$1\":\"" + AirbyteSecretConstants.SECRETS_MASK + "\"";

  /**
   * The pattern used to determine if a message contains sensitive data.
   */
  private final Optional<Pattern> pattern;

  private static final List<Pattern> KNOWN_PII_PATTERNS = List.of(
      Pattern.compile(DESTINATION_ERROR_PREFIX + "(?<messagePrefix>Received\\s+invalid\\s+message:)(.+)$"),
      Pattern.compile(DESTINATION_ERROR_PREFIX + "(?<messagePrefix>org\\.jooq\\.exception\\.DataAccessException: SQL.+values\\s+\\()(.+)$"));

  @PluginFactory
  public static MaskedDataInterceptor createPolicy(
                                                   @PluginAttribute(value = "specMaskFile",
                                                                    defaultString = LOCAL_SECRETS_MASKS_PATH) final String specMaskFile) {
    return new MaskedDataInterceptor(specMaskFile);
  }

  private MaskedDataInterceptor(final String specMaskFile) {
    this.pattern = buildPattern(specMaskFile);
  }

  @Override
  public LogEvent rewrite(final LogEvent source) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName(source.getLoggerName())
        .setMarker(source.getMarker())
        .setLoggerFqcn(source.getLoggerFqcn())
        .setLevel(source.getLevel())
        .setMessage(new SimpleMessage(applyMask(source.getMessage().getFormattedMessage())))
        .setThrown(source.getThrown())
        .setContextMap(source.getContextMap())
        .setContextStack(source.getContextStack())
        .setThreadName(source.getThreadName())
        .setSource(source.getSource())
        .setTimeMillis(source.getTimeMillis())
        .build();
  }

  /**
   * Applies the mask to the message, if necessary.
   *
   * @param message The log message.
   * @return The possibly masked log message.
   */
  private String applyMask(final String message) {
    final String piiScrubbedMessage = removeKnownPii(message);
    return pattern.map(p -> p.matcher(piiScrubbedMessage).replaceAll(REPLACEMENT_PATTERN))
        .orElse(piiScrubbedMessage);
  }

  /**
   * Removes known PII from the message.
   *
   * @param message the log line
   * @return a redacted log line
   */
  private static String removeKnownPii(final String message) {
    return KNOWN_PII_PATTERNS.stream()
        .reduce(message, (msg, pattern) -> pattern.matcher(msg).replaceAll(
            KNOWN_PII_LOG_MESSAGE_REPLACEMENT_PATTERN), (a, b) -> a);
  }

  /**
   * Loads the maskable properties from the provided file.
   *
   * @param specMaskFile The spec mask file.
   * @return The set of maskable properties.
   */
  private Set<String> getMaskableProperties(final String specMaskFile) {
    logger.info("Loading mask data from '{}", specMaskFile);
    try {
      final String maskFileContents = IOUtils.toString(getClass().getResourceAsStream(specMaskFile), Charset.defaultCharset());
      final Map<String, Set<String>> properties = Jsons.object(Yamls.deserialize(maskFileContents), new TypeReference<>() {});
      return properties.getOrDefault(PROPERTIES_KEY, Set.of());
    } catch (final Exception e) {
      logger.error("Unable to load mask data from '{}': {}.", specMaskFile, e.getMessage());
      return Set.of();
    }
  }

  /**
   * Builds the maskable property matching pattern.
   *
   * @param specMaskFile The spec mask file.
   * @return The regular expression pattern used to find maskable properties.
   */
  private Optional<Pattern> buildPattern(final String specMaskFile) {
    final Set<String> maskableProperties = getMaskableProperties(specMaskFile);
    return !maskableProperties.isEmpty() ? Optional.of(Pattern.compile(generatePattern(maskableProperties))) : Optional.empty();
  }

  /**
   * Generates the property matching pattern string from the provided set of properties.
   *
   * @param properties The set of properties to match.
   * @return The generated regular expression pattern used to match the maskable properties.
   */
  private String generatePattern(final Set<String> properties) {
    final StringBuilder builder = new StringBuilder();
    builder.append(CASE_INSENSITIVE_FLAG);
    builder.append(PROPERTY_MATCHING_PATTERN_PREFIX);
    builder.append(properties.stream().collect(Collectors.joining(PROPERTY_MATCHING_PATTERN_DELIMITER)));
    builder.append(PROPERTY_MATCHING_PATTERN_SUFFIX);
    return builder.toString();
  }

}
