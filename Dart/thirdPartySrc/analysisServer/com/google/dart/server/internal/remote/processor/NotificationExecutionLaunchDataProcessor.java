/*
 * Copyright (c) 2014, the Dart project authors.
 * 
 * Licensed under the Eclipse Public License v1.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.dart.server.internal.remote.processor;

import com.google.dart.server.AnalysisServerListener;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Instances of the class {@code NotificationExecutionLaunchDataProcessor} process
 * "execution.launchData" notifications.
 * 
 * @coverage dart.server.remote
 */
public class NotificationExecutionLaunchDataProcessor extends NotificationProcessor {
  /**
   * Initialize a newly created processor to report to the given listener.
   * 
   * @param listener the listener that should be notified when the notification is received
   */
  public NotificationExecutionLaunchDataProcessor(AnalysisServerListener listener) {
    super(listener);
  }

  @Override
  public void process(JsonObject response) throws Exception {
    JsonObject paramsObject = response.get("params").getAsJsonObject();
    String file = paramsObject.get("file").getAsString();
    JsonElement jsonKind = paramsObject.get("kind");
    String kind = jsonKind == null ? null : jsonKind.getAsString();
    JsonElement jsonFiles = paramsObject.get("referencedFiles");
    JsonArray referencedFiles = jsonFiles == null ? null : jsonFiles.getAsJsonArray();
    getListener().computedLaunchData(file, kind, constructStringArray(referencedFiles));
  }
}
