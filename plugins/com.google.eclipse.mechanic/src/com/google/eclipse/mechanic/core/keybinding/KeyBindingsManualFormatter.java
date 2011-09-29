/*******************************************************************************
 * Copyright (C) 2011, Google Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package com.google.eclipse.mechanic.core.keybinding;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.eclipse.mechanic.core.keybinding.KbaChangeSet.KbaBindingList;
import com.google.eclipse.mechanic.plugin.core.MechanicLog;

import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.bindings.Binding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Class that formats the keybindings by sweat and tears.
 * 
 * <p>The reason a JSON formatter is not used is so that the output, which is
 * meant to be read by a person, is carefully tinkered with to be readable.
 *
 * <p>The usage for this class is the following: 1. user creates a few
 * keybindings through the regular Eclipse UI; 2. user wants to create an audit
 * based on these bindings; 3. user triggers this code (still TODO) which spits
 * out a file for him/her to bootstrap. This will be the canonical, documented
 * and simplest way to create a .kbd audit file.
 *
 * @author zorzella@google.com
 */
class KeyBindingsManualFormatter {

  private final boolean debugDumpSystemBindings;
  private final MechanicLog log;
  private final File tempDir;
  private final Map<KbaChangeSetQualifier, KbaChangeSet> userBindingsMap;
  private final Map<KbaChangeSetQualifier, KbaChangeSet> systemBindingsMap;

  /**
   * Creates a new instance from a defined set of bindings.
   */
  public KeyBindingsManualFormatter(
      final MechanicLog log,
      final Multimap<KbaChangeSetQualifier, Binding> userBindingsMap,
      final Multimap<KbaChangeSetQualifier, Binding> systemBindingsMap) {
    this(System.getProperty("KEYBOARD_MECHANIC_DEBUG_DUMP_SYSTEM_BINDINGS", "false").equals("true"),
        log,
        tempDir(),
        transform(userBindingsMap),
        transform(systemBindingsMap));
  }

  private static File tempDir() {
    String dirName = System.getProperty("java.io.tmpdir");
    File file = new File(dirName, "workspace-mechanic-kbd");
    file.mkdir();
    file.deleteOnExit();
    return file;
  }
  
  public KeyBindingsManualFormatter(
      final boolean debugDumpSystemBindings,
      final MechanicLog log,
      final File tempDir,
      final Map<KbaChangeSetQualifier, KbaChangeSet> userBindingsMap,
      final Map<KbaChangeSetQualifier, KbaChangeSet> systemBindingsMap) {
    this.debugDumpSystemBindings = debugDumpSystemBindings;
    this.log = log;
    this.tempDir = tempDir;
    this.userBindingsMap = userBindingsMap;
    this.systemBindingsMap = systemBindingsMap;
  }

  private static Map<KbaChangeSetQualifier, KbaChangeSet> transform(Multimap<KbaChangeSetQualifier, Binding> orig) {
    Map<KbaChangeSetQualifier, KbaChangeSet> result = Maps.newHashMap();
    for (KbaChangeSetQualifier q : orig.keySet()) {
      Collection<Binding> bindings = orig.get(q);
      Function<Binding, KbaBinding> function = new Function<Binding, KbaBinding>() {
        public KbaBinding apply(Binding binding) {
          ParameterizedCommand cmd = binding.getParameterizedCommand();
          if (cmd == null) {
            // TODO a null command means a remove. Implement removes. For now,
            // we expect these to have been not sent.
            throw new UnsupportedOperationException();
          }
          Map<String,String> parameterMap = paramMap(cmd);
          return new KbaBinding(
              binding.getTriggerSequence().format(),
              cmd.getId(),
              parameterMap);
        }

        @SuppressWarnings({ "unchecked", "cast" })
        private Map<String,String> paramMap(ParameterizedCommand cmd) {
          if (cmd == null) {
            return Collections.emptyMap();
          }
          @SuppressWarnings("rawtypes")
          Map tmp = cmd.getParameterMap();
          if (tmp == null) {
            return Collections.emptyMap();
          }
          return (Map<String,String>)tmp;
        }
      };
      Iterable<KbaBinding> transformed = Iterables.transform(bindings, function);
      KbaBindingList bindingSpecList = new KbaBindingList(transformed);
      KbaChangeSet changeSet = new KbaChangeSet(
          q.scheme,
          q.platform,
          q.context,
          KbaChangeSet.Action.ADD.toString(),
          bindingSpecList);
      result.put(q, changeSet);
    }
    return result;
  }

  enum BindingType {
    USER,
    SYSTEM,
    ;
  }

  void dumpBindingsToFile() {
    if (debugDumpSystemBindings) {
      dumpBindingsToFile(BindingType.SYSTEM, systemBindingsMap);
    }
    dumpBindingsToFile(BindingType.USER, userBindingsMap);
  }

  
  private void dumpBindingsToFile(BindingType bindingType, Map<KbaChangeSetQualifier, KbaChangeSet> kbaChangeSet) {
    String output = getBindingsPrintout(bindingType, kbaChangeSet);
    try {
      File tempFile = new File(tempDir, "CURRENT-" + bindingType + ".kbd");
      tempFile.deleteOnExit();
      PrintStream stream = new PrintStream(new FileOutputStream(tempFile));
      stream.print(output);
      log.log(IStatus.OK, "Successfully wrote '%s'", tempFile.getAbsolutePath());
    } catch (Exception e) {
      log.logError(e);
    }
  }
  
  static String getBindingsPrintout(BindingType bindingType, Map<KbaChangeSetQualifier,KbaChangeSet> bindings) {
    StringBuilder output = new StringBuilder()
        .append("{\n")
        .append(i(1)).append(quote(KeyBindingsParser.METADATA_JSON_KEY)).append(" : {\n")
        .append(i(2)).append(kvcn(KeyBindingsParser.DESCRIPTION_JSON_KEY, "Put a long description here"))
        .append(i(2)).append(kvn(KeyBindingsParser.TYPE_JSON_KEY, "LASTMOD"))
        .append(i(1)).append("},\n")
        .append(i(1)).append(quote(KeyBindingsParser.CHANGE_SETS_JSON_KEY)).append(" : [\n");
    for (KbaChangeSetQualifier q : bindings.keySet()) {
      output
          .append(i(2)).append("{\n")
          .append(i(2)).append(kvcn(KeyBindingsParser.SCHEME_JSON_KEY, q.scheme));
      
      if (q.platform != null) {
        output
            .append(i(2)).append(kvcn(KeyBindingsParser.PLATFORM_JSON_KEY, q.platform));
      }
      output
          .append(i(2)).append(kvcn(KeyBindingsParser.CONTEXT_JSON_KEY, q.context))
          .append(i(2)).append(kvcn(KeyBindingsParser.ACTION_JSON_KEY, KeyBindingsParser.ADD_JSON_KEY))
          .append(i(2)).append(quote(KeyBindingsParser.BINDINGS_JSON_KEY)).append(" : [\n");
      for (KbaBinding b : bindings.get(q).getBindingList()) {
        output.append(formatKbaBinding(b));
        
        // TODO: GSON is not happy about trailing commas. Either make
        // GSON happy, or suppress trailing whitespaces
      }
      output.append(i(3)).append("]\n")
          .append(i(2)).append("},\n");
    }
    output.append(i(1)).append("]\n");
    output.append("}");
    return output.toString();
  }

  private static CharSequence formatKbaBinding(KbaBinding b) {
      boolean remove = false;
      StringBuilder toPrint = new StringBuilder()
          .append(i(3))
              .append("{")
              .append(kvcs(KeyBindingsParser.KEYS_JSON_KEY, b.getKeySequence()))
              .append(formatCommand(b))
              .append("},\n");
      return toPrint;
  }

  private static String formatCommand(KbaBinding b) {
    StringBuilder result = new StringBuilder()
        .append(kv(KeyBindingsParser.COMMAND_JSON_KEY, b.getCid()));
    
    Map<String,String> parameterMap = b.getParameters();
    if (parameterMap.size() > 0) {
      result.append(", ")
          .append(kd(KeyBindingsParser.COMMAND_PARAMETERS_JSON_KEY, formatParameters(parameterMap)));
    }
    return result.toString();
  }

  private static String formatParameters(final Map<String, String> parameterMap) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    
    Function<String,String> function = new Function<String, String>() {
      public String apply(String key) {
        return kv(urlEncoded(key), urlEncoded(parameterMap.get(key)));
      }
    };
    Iterable<String> transformed = Iterables.transform(parameterMap.keySet(), function);
    sb.append(Joiner.on(", ").join(transformed));
    
    sb.append("}");
    return sb.toString();
  }

  private static String urlEncoded(String key) {
    try {
      return URLEncoder.encode(key, "US-ASCII");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  // Beging quick-and-dirty methods to help us format the output

  private static String i(int indentNumber) {
    StringBuilder result = new StringBuilder();
    for (int i=0; i<indentNumber; i++) {
      result.append("  ");
    }
    return result.toString();
  }
  
 private static String kd(String key, String data) {
    return quote(key) + " : " + data;
  }
  
  private static String kvn(String key, String value) {
    return quote(key) + " : " + quote(value) + "\n";
  }
  
  private static String kv(String key, String value) {
    return quote(key) + " : " + quote(value);
  }
  
  private static String kvcs(String key, String value) {
    return quote(key) + " : " + quote(value) + ", ";
  }
  
  private static String kvcn(String key, String value) {
    return quote(key) + " : " + quote(value) + ",\n";
  }

  private static String quote(String string) {
    return "'" + string + "'";
  }
}