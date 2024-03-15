package com.scratchyone.velocitykuberouter;

import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.language.object.YamlSerializable;

public class Config extends YamlSerializable {
  private static final SerializerConfig CONFIG =
      new SerializerConfig.Builder().setCommentValueIndent(1).build();

  @Comment(value = @CommentValue("Enables or disables debug logging"))
  public boolean debug = false;

  public Config() {
    super(Config.CONFIG);
  }
}
