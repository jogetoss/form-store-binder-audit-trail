package org.joget.marketplace;

import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.UUID;

import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.*;
import org.joget.apps.form.service.FormUtil;
import org.joget.apps.form.lib.WorkflowFormBinder;
import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Data binder that loads/stores data from the form database and also workflow
 * variables.
 */
public class WorkflowFormBinderWithAuditTrail extends WorkflowFormBinder {

    private final static String MESSAGE_PATH = "messages/form/WorkflowFormBinderWithAuditTrail";

    @Override
    public String getName() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.name", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.desc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getLabel() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.name", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/form/WorkflowFormBinderWithAuditTrail.json", null, true, MESSAGE_PATH);
    }

    @Override
    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        return super.load(element, primaryKey, formData);
    }

    @Override
    public FormRowSet store(Element element, FormRowSet rows, FormData formData) {
        AppService appService = (AppService) FormUtil.getApplicationContext().getBean("appService");

        if (formData.getLoadBinderData(element) != null) {
            String primaryKey = formData.getPrimaryKeyValue();
            String auditTrailFormID = getPropertyString("auditTrailFormId");
            String auditTrailDiffField = getPropertyString("jsonDataField");
            String auditTrailTextDiffField = getPropertyString("textualDataField");
            String auditTrailRemarksField = getPropertyString("from");
            String auditTrailRemarksColumn = getPropertyString("to");
            boolean tracksEverything = Boolean.parseBoolean(getPropertyString("tracksEverything"));

            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            FormDefinitionDao formDefinitionDao = (FormDefinitionDao) FormUtil.getApplicationContext().getBean("formDefinitionDao");
            FormDefinition formDef = formDefinitionDao.loadById(auditTrailFormID, appDef);
            String auditTrailTableName = formDef.getTableName();


            String auditTrailTableForeignKey = getPropertyString("foreignKey");

            //get the previous dataset (n-1)
            FormRowSet existingData = formData.getLoadBinderData(element);

            //find differences
            MapDifference diff = Maps.difference((Map) existingData.get(0), (Map) rows.get(0));
            JSONArray jsonArray = new JSONArray();

            Map differences = diff.entriesDiffering();
            String text = "";

            for (Object obj : differences.keySet()) {
                String id = element.getPropertyString(FormUtil.PROPERTY_ID);

                ValueDifference vd = ((ValueDifference) differences.get(obj));
                String fieldID = obj.toString();
                String before = vd.leftValue().toString();
                String after = vd.rightValue().toString();
                String beforeContentID = "";
                String afterContentID = "";

                JSONObject jsonDiff = new JSONObject();
                try {
                    //Get element of field and its properties to check and extract data
                    Element controlElement = FormUtil.findElement(fieldID, element, formData);
                    Map<String, Object> controlElementPropertyOptions = controlElement.getProperties();
                    JSONObject jsonProperty = new JSONObject(controlElement.getDefaultPropertyValues());
                    String label = controlElementPropertyOptions.get("label").toString();

                    jsonDiff.accumulate("fieldID", fieldID); //i.e. full_name
                    jsonDiff.accumulate("fieldLabel", label); //i.e. full_name

                    if (jsonProperty.get("label").equals("Select Box")) {
                        FormRowSet selectRows = formData.getOptionsBinderData(controlElement, fieldID);
                        String beforeContentLabel = "";
                        String afterContentLabel = "";
                        for (FormRow selectRow : selectRows) {
                            if (selectRow.get("value").equals(before)) {
                                jsonDiff.accumulate("beforeContentID", selectRow.get("value"));
                                beforeContentID = (String) selectRow.get("value");
                                jsonDiff.accumulate("beforeContentValue", (String) selectRow.get("label")); //raw value, if it is a ID of a lookup, we need to populate the label
                                beforeContentLabel = selectRow.get("label").toString();
                            }
                            if (selectRow.get("value").equals(after)) {
                                jsonDiff.accumulate("afterContentId", selectRow.get("value"));
                                afterContentID = (String) selectRow.get("value");
                                jsonDiff.accumulate("afterContentValue", (String) selectRow.get("label")); //raw value, if it is a ID of a lookup, we need to populate the label
                                afterContentLabel = selectRow.get("label").toString();

                            }
                            if (!beforeContentID.equals("") && !afterContentID.equals("")) {
                                text += "Field Label: " + label + ",\nField ID: " + fieldID + ","
                                        + "\nBefore Content Value: " + beforeContentLabel + ",\nAfter Content Value: " + afterContentLabel + ","
                                        + "\nBefore ID Value: " + beforeContentID + ",\nAfter ID Value: " + afterContentID + "\n\n";
                                break;
                            }
                        }
                    } else {
                        //skip remarks field
                        if (fieldID.equals(auditTrailRemarksField)) {
                            continue;
                        }
                        jsonDiff.accumulate("afterContentValue", after); //raw value, if it is a ID of a lookup, we need to populate the label
                        jsonDiff.accumulate("beforeContentValue", before); //raw value, if it is a ID of a lookup, we need to populate the label
                        text += "Field Label: " + label + ",\nField ID: " + fieldID + ","
                                + "\nBefore Content Value: " + before + ",\nAfter Content Value: " + after + "\n\n";
                    }
                } catch (JSONException ex) {
                    LogUtil.error(this.getClassName(), ex, "Error building changes object");
                }
                jsonArray.put(jsonDiff);

            }

            //only if there is changes
            if(jsonArray.length() != 0 || tracksEverything) {
                //store changes into n
                FormRow currentRow = rows.get(0);
                currentRow.put("id", primaryKey);
                currentRow.put(auditTrailDiffField, jsonArray.toString());
                currentRow.put(auditTrailTextDiffField, text);
                currentRow.put(auditTrailRemarksColumn, rows.get(0).get(auditTrailRemarksField));


                //added empty row
                rows.remove(0);
                rows.add(currentRow);

                //retrieve n-1
                FormRowSet auditRows = new FormRowSet();
                FormRow currentTemp = rows.get(0);
                currentTemp.put(auditTrailTableForeignKey, primaryKey);
                currentTemp.setId(UUID.randomUUID().toString());
                auditRows.add(currentTemp);
                appService.storeFormData(auditTrailFormID, auditTrailTableName, auditRows, null);
                rows.get(0).setId(primaryKey);
            }

        }

        //proceed as usual
        return super.store(element, rows, formData);
    }
}
