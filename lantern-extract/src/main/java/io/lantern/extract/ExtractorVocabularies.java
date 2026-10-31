package io.lantern.extract;

import io.lantern.core.vocabulary.VocabularyType;
import io.lantern.extract.config.ConfigExtractor;
import io.lantern.extract.deployment.DeploymentExtractor;
import io.lantern.extract.docs.DocsExtractor;
import io.lantern.extract.frontend.FrontendExtractor;
import io.lantern.extract.gateway.GatewayExtractor;
import io.lantern.extract.http.HttpClientExtractor;
import io.lantern.extract.identity.IdentityExtractor;
import io.lantern.extract.java.JavaExtractor;
import io.lantern.extract.mail.MailExtractor;
import io.lantern.extract.maven.MavenExtractor;
import io.lantern.extract.messaging.MessagingExtractor;
import io.lantern.extract.people.PeopleExtractor;
import io.lantern.extract.persistence.PersistenceExtractor;
import io.lantern.extract.redis.RedisExtractor;
import io.lantern.extract.spring.SpringExtractor;
import io.lantern.extract.sql.SqlExtractor;
import io.lantern.extract.test.TestExtractor;
import io.lantern.extract.websocket.WebSocketExtractor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/** The vocabulary types of every extractor, by vocabulary source. */
public final class ExtractorVocabularies {

  private ExtractorVocabularies() {}

  /**
   * Each extractor's types by its vocabulary source, in the order they are applied: a type's parent
   * comes from the built-in types or a source before it.
   */
  public static SequencedMap<String, List<VocabularyType>> bySource() {
    SequencedMap<String, List<VocabularyType>> sources = new LinkedHashMap<>();
    sources.put(MavenExtractor.VOCABULARY_SOURCE, MavenExtractor.vocabulary());
    sources.put(JavaExtractor.VOCABULARY_SOURCE, JavaExtractor.vocabulary());
    sources.put(ConfigExtractor.VOCABULARY_SOURCE, ConfigExtractor.vocabulary());
    sources.put(DocsExtractor.VOCABULARY_SOURCE, DocsExtractor.vocabulary());
    sources.put(SqlExtractor.VOCABULARY_SOURCE, SqlExtractor.vocabulary());
    sources.put(SpringExtractor.VOCABULARY_SOURCE, SpringExtractor.vocabulary());
    sources.put(PersistenceExtractor.VOCABULARY_SOURCE, PersistenceExtractor.vocabulary());
    sources.put(HttpClientExtractor.VOCABULARY_SOURCE, HttpClientExtractor.vocabulary());
    sources.put(MessagingExtractor.VOCABULARY_SOURCE, MessagingExtractor.vocabulary());
    sources.put(MailExtractor.VOCABULARY_SOURCE, MailExtractor.vocabulary());
    sources.put(IdentityExtractor.VOCABULARY_SOURCE, IdentityExtractor.vocabulary());
    sources.put(RedisExtractor.VOCABULARY_SOURCE, RedisExtractor.vocabulary());
    sources.put(PeopleExtractor.VOCABULARY_SOURCE, PeopleExtractor.vocabulary());
    sources.put(DeploymentExtractor.VOCABULARY_SOURCE, DeploymentExtractor.vocabulary());
    sources.put(FrontendExtractor.VOCABULARY_SOURCE, FrontendExtractor.vocabulary());
    sources.put(GatewayExtractor.VOCABULARY_SOURCE, GatewayExtractor.vocabulary());
    sources.put(TestExtractor.VOCABULARY_SOURCE, TestExtractor.vocabulary());
    sources.put(WebSocketExtractor.VOCABULARY_SOURCE, WebSocketExtractor.vocabulary());
    return sources;
  }
}
