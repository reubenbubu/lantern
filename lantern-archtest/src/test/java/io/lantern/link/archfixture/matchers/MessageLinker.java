package io.lantern.link.archfixture.matchers;

/** A messaging matcher that reads a message fact's attributes, which the rule rejects. */
public final class MessageLinker {

  public String link(MessageSendAttributes send) {
    return send.broker();
  }
}
