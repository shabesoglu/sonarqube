/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.source.ws;

import com.google.common.io.Resources;
import org.apache.commons.lang.ObjectUtils;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.server.component.ComponentService;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.source.HtmlSourceDecorator;
import org.sonar.server.source.index.SourceLineDoc;
import org.sonar.server.source.index.SourceLineIndex;
import org.sonar.server.user.UserSession;

import java.util.Date;
import java.util.List;

public class LinesAction implements RequestHandler {

  private final SourceLineIndex sourceLineIndex;
  private final HtmlSourceDecorator htmlSourceDecorator;
  private final ComponentService componentService;

  public LinesAction(SourceLineIndex sourceLineIndex, HtmlSourceDecorator htmlSourceDecorator, ComponentService componentService) {
    this.sourceLineIndex = sourceLineIndex;
    this.htmlSourceDecorator = htmlSourceDecorator;
    this.componentService = componentService;
  }

  void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction("lines")
      .setDescription("Show source code with line oriented info. Require See Source Code permission on file's project<br/>" +
        "Each element of the result array is an object which contains:" +
        "<ol>" +
        "<li>Line number</li>" +
        "<li>Content of the line</li>" +
        "<li>Author of the line (from SCM information)</li>" +
        "<li>Revision of the line (from SCM information)</li>" +
        "<li>Last commit date of the line (from SCM information)</li>" +
        "</ol>")
      .setSince("5.0")
      .setInternal(true)
      .setResponseExample(Resources.getResource(getClass(), "example-lines.json"))
      .setHandler(this);

    action
      .createParam("uuid")
      .setRequired(true)
      .setDescription("File uuid")
      .setExampleValue("f333aab4-7e3a-4d70-87e1-f4c491f05e5c");

    action
      .createParam("from")
      .setDescription("First line to return. Starts at 1")
      .setExampleValue("10")
      .setDefaultValue("1");

    action
      .createParam("to")
      .setDescription("Last line to return (inclusive)")
      .setExampleValue("20");
  }

  @Override
  public void handle(Request request, Response response) {
    String fileUuid = request.mandatoryParam("uuid");
    ComponentDto component = componentService.getByUuid(fileUuid);
    UserSession.get().checkComponentPermission(UserRole.CODEVIEWER, component.key());

    int from = Math.max(request.mandatoryParamAsInt("from"), 1);
    int to = (Integer) ObjectUtils.defaultIfNull(request.paramAsInt("to"), Integer.MAX_VALUE);

    List<SourceLineDoc> sourceLines = sourceLineIndex.getLines(fileUuid, from, to);
    if (sourceLines.isEmpty()) {
      throw new NotFoundException("File '" + fileUuid + "' has no sources");
    }

    JsonWriter json = response.newJsonWriter().beginObject();
    writeSource(sourceLines, from, json);

    json.endObject().close();
  }

  private void writeSource(List<SourceLineDoc> lines, int from, JsonWriter json) {
    json.name("sources").beginArray();
    for (SourceLineDoc line: lines) {
      json.beginObject()
        .prop("line", line.line())
        .prop("code", htmlSourceDecorator.getDecoratedSourceAsHtml(line.source(), line.highlighting(), line.symbols()))
        .prop("scmAuthor", line.scmAuthor())
        .prop("scmRevision", line.scmRevision());
      Date scmDate = line.scmDate();
      json.prop("scmDate", scmDate == null ? null : DateUtils.formatDateTime(scmDate));
      json.prop("lineHits", line.overallLineHits())
        .prop("conditions", line.overallConditions())
        .prop("coveredConditions", line.overallCoveredConditions());
      if (! line.duplications().isEmpty()) {
        json.prop("duplicated", true);
      }
      json.endObject();
    }
    json.endArray();
  }
}
