package com.trail2peak.pdi.fastjsoninput;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;

import org.apache.commons.vfs2.FileObject;
import org.json.simple.JSONObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.fileinput.FileInputList;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

/**
 * Read Json files, parse them and convert them to rows and writes these to one
 * or more output streams.
 * 
 * This is an alternate version of the "JSON Input" step that uses Jayway JsonPath
 * (https://github.com/jayway/JsonPath) instead of a parser based on JavaScript. It
 * is intended to be a drop-in replacement for the "JSON Input" step but should
 * be much faster and memory efficient.
 *
 * @author Samatar
 * @author edube
 * @author jadametz
 * @since 2015-08-18
 */
public class FastJsonInput extends BaseStep implements StepInterface {
	private static Class<?> PKG = FastJsonInputMeta.class; // for i18n purposes,
															// needed by
															// Translator2!!

	private FastJsonInputMeta meta;
	private FastJsonInputData data;

	public FastJsonInput(StepMeta stepMeta,
			StepDataInterface stepDataInterface, int copyNr,
			TransMeta transMeta, Trans trans) {
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}

	/**
	 * Build an empty row based on the meta-data.
	 *
	 * @return
	 */
	private Object[] buildEmptyRow() {
		Object[] rowData = RowDataUtil.allocateRowData(data.outputRowMeta
				.size());

		return rowData;
	}

	private void handleMissingFiles() throws KettleException {
		List<FileObject> nonExistantFiles = data.files.getNonExistantFiles();
		if (nonExistantFiles.size() != 0) {
			String message = FileInputList
					.getRequiredFilesDescription(nonExistantFiles);
			log.logError(BaseMessages.getString(PKG,
					"FastJsonInput.Log.RequiredFilesTitle"), BaseMessages
					.getString(PKG, "FastJsonInput.Log.RequiredFiles", message));

			throw new KettleException(BaseMessages.getString(PKG,
					"FastJsonInput.Log.RequiredFilesMissing", message));
		}

		List<FileObject> nonAccessibleFiles = data.files
				.getNonAccessibleFiles();
		if (nonAccessibleFiles.size() != 0) {
			String message = FileInputList
					.getRequiredFilesDescription(nonAccessibleFiles);
			log.logError(BaseMessages.getString(PKG,
					"FastJsonInput.Log.RequiredFilesTitle"), BaseMessages
					.getString(PKG,
							"FastJsonInput.Log.RequiredNotAccessibleFiles",
							message));

			throw new KettleException(BaseMessages.getString(PKG,
					"FastJsonInput.Log.RequiredNotAccessibleFilesMissing",
					message));
		}
	}

	private boolean readNextString() {

		try {
			data.readrow = getRow(); // Grab another row ...

			if (data.readrow == null) {
				// finished processing!
				if (log.isDetailed()) {
					logDetailed(BaseMessages.getString(PKG,
							"FastJsonInput.Log.FinishedProcessing"));
				}
				return false;
			}

			if (first) {
				first = false;

				data.inputRowMeta = getInputRowMeta();
				data.outputRowMeta = data.inputRowMeta.clone();

				// Check if source field is provided
				if (Const.isEmpty(meta.getFieldValue())) {
					logError(BaseMessages.getString(PKG,
							"FastJsonInput.Log.NoField"));
					throw new KettleException(BaseMessages.getString(PKG,
							"FastJsonInput.Log.NoField"));
				}

				// cache the position of the field
				if (data.indexSourceField < 0) {
					data.indexSourceField = getInputRowMeta().indexOfValue(
							meta.getFieldValue());
					if (data.indexSourceField < 0) {
						// The field is unreachable !
						logError(BaseMessages.getString(PKG,
								"FastJsonInput.Log.ErrorFindingField",
								meta.getFieldValue()));
						throw new KettleException(BaseMessages.getString(PKG,
								"FastJsonInput.Exception.CouldnotFindField",
								meta.getFieldValue()));
					}
				}
				
				// if RemoveSourceField option is set, we remove the source field from the output meta
				if (meta.isRemoveSourceField()) {
					data.outputRowMeta.removeValueMeta(data.indexSourceField);
					// Get total previous fields minus one since we remove source field
					data.totalpreviousfields = data.inputRowMeta.size() - 1;
				} else {
					// Get total previous fields
					data.totalpreviousfields = data.inputRowMeta.size();
				}
				
				meta.getFields(data.outputRowMeta, getStepname(), null, null,
						this, repository, metaStore);



				// Create convert meta-data objects that will contain Date &
				// Number formatters
				data.convertRowMeta = data.outputRowMeta
						.cloneToType(ValueMetaInterface.TYPE_STRING);

			}

			// get source field value
			String fieldValue = getInputRowMeta().getString(data.readrow,
					data.indexSourceField);

			if (log.isDetailed()) {
				logDetailed(BaseMessages.getString(PKG,
						"FastJsonInput.Log.SourceValue", meta.getFieldValue(),
						fieldValue));
			}

			if (meta.getIsAFile()) {

				// source is a file.
				data.file = KettleVFS.getFileObject(fieldValue, getTransMeta());
				if (meta.isIgnoreEmptyFile()
						&& data.file.getContent().getSize() == 0) {
					// log only basic as a warning (was before logError)
					logBasic(BaseMessages.getString(PKG,
							"FastJsonInput.Error.FileSizeZero",
							data.file.getName()));
					readNextString();
				}
			} else {
				// read string
				data.stringToParse = fieldValue;
			}

			readFileOrString();
		} catch (Exception e) {
			logError(BaseMessages.getString(PKG,
					"FastJsonInput.Log.UnexpectedError", e.toString()));
			stopAll();
			logError(Const.getStackTracker(e));
			setErrors(1);
			return false;
		}
		return true;

	}

	private void addFileToResultFilesname(FileObject file) throws Exception {
		if (meta.addResultFile()) {
			// Add this to the result file names...
			ResultFile resultFile = new ResultFile(
					ResultFile.FILE_TYPE_GENERAL, file, getTransMeta()
							.getName(), getStepname());
			resultFile.setComment(BaseMessages.getString(PKG,
					"FastJsonInput.Log.FileAddedResult"));
			addResultFile(resultFile);
		}
	}

	private boolean openNextFile() {
		try {
			if (data.filenr >= data.files.nrOfFiles()) {
				if (log.isDetailed()) {
					logDetailed(BaseMessages.getString(PKG,
							"FastJsonInput.Log.FinishedProcessing"));
				}
				return false;
			}
			// Close previous file if needed
			if (data.file != null) {
				data.file.close();
			}
			// get file
			data.file = data.files.getFile(data.filenr);
			if (meta.isIgnoreEmptyFile()
					&& data.file.getContent().getSize() == 0) {
				// log only basic as a warning (was before logError)
				logBasic(BaseMessages.getString(PKG,
						"FastJsonInput.Error.FileSizeZero",
						"" + data.file.getName()));
				openNextFile();
			}
			readFileOrString();
		} catch (Exception e) {
			logError(BaseMessages.getString(PKG,
					"FastJsonInput.Log.UnableToOpenFile", "" + data.filenr,
					data.file.toString(), e.toString()));
			stopAll();
			setErrors(1);
			return false;
		}
		return true;
	}

	private void readFileOrString() throws Exception {
		if (data.file != null) {
			data.filename = KettleVFS.getFilename(data.file);
			// Add additional fields?
			if (meta.getShortFileNameField() != null
					&& meta.getShortFileNameField().length() > 0) {
				data.shortFilename = data.file.getName().getBaseName();
			}
			if (meta.getPathField() != null && meta.getPathField().length() > 0) {
				data.path = KettleVFS.getFilename(data.file.getParent());
			}
			if (meta.isHiddenField() != null
					&& meta.isHiddenField().length() > 0) {
				data.hidden = data.file.isHidden();
			}
			if (meta.getExtensionField() != null
					&& meta.getExtensionField().length() > 0) {
				data.extension = data.file.getName().getExtension();
			}
			if (meta.getLastModificationDateField() != null
					&& meta.getLastModificationDateField().length() > 0) {
				data.lastModificationDateTime = new Date(data.file.getContent()
						.getLastModifiedTime());
			}
			if (meta.getUriField() != null && meta.getUriField().length() > 0) {
				data.uriName = data.file.getName().getURI();
			}
			if (meta.getRootUriField() != null
					&& meta.getRootUriField().length() > 0) {
				data.rootUriName = data.file.getName().getRootURI();
			}
			// Check if file is empty
			long fileSize = data.file.getContent().getSize();

			if (meta.getSizeField() != null && meta.getSizeField().length() > 0) {
				data.size = fileSize;
			}
			// Move file pointer ahead!
			data.filenr++;

			if (log.isDetailed()) {
				logDetailed(BaseMessages.getString(PKG,
						"FastJsonInput.Log.OpeningFile", data.file.toString()));
			}

			addFileToResultFilesname(data.file);
		}

		parseJson();

	}

	private void parseJson() throws Exception {

		// Read JSON source
		if (data.file != null) {
			data.jsonReader.readFile(data.filename);
		} else {
			if (meta.isReadUrl()) {
				data.jsonReader.readUrl(data.stringToParse);
			} else {
				// read string
				data.jsonReader.readString(data.stringToParse);
			}
		}
		List<JsonResultList> resultList = new ArrayList<JsonResultList>();
		data.nrrecords = -1;
		data.recordnr = 0;
		String prevPath = "";
		for (int i = 0; i < data.nrInputFields; i++) {
			String path = meta.getInputFields()[i].getPath();
			JsonResultList ja = data.jsonReader.getPath(path);
			if (data.nrrecords != -1 && data.nrrecords != ja.size()
					&& !ja.isNull()) {
				throw new KettleException(BaseMessages.getString(PKG,
						"FastJsonInput.Error.BadStructure", ja.size(), path,
						prevPath, data.nrrecords));
			}
			resultList.add(ja);
			if (data.nrrecords == -1 && !ja.isNull()) {
				data.nrrecords = ja.size();
			}
			prevPath = path;
		}

		data.resultList = new ArrayList<JsonResultList>();

		Iterator<JsonResultList> it = resultList.iterator();

		while (it.hasNext()) {
			JsonResultList j = it.next();
			if (j.isNull()) {
				if (data.nrrecords == -1) {
					data.nrrecords = 1;
				}
				// The object is empty means that we do not
				// find Json path
				// We need here to create a dummy structure
				j = new JsonResultList();
				for (int i = 0; i < data.nrrecords; i++) {
					j.add(null);
				}
			}
			data.resultList.add(j);
		}
		resultList = null;

		if (log.isDetailed()) {
			logDetailed(BaseMessages.getString(PKG,
					"FastJsonInput.Log.NrRecords", data.nrrecords));
		}

	}

	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi)
			throws KettleException {
		if (first && !meta.isInFields()) {
			first = false;

			data.files = meta.getFiles(this);

			if (!meta.isdoNotFailIfNoFile() && data.files.nrOfFiles() == 0) {
				throw new KettleException(BaseMessages.getString(PKG,
						"FastJsonInput.Log.NoFiles"));
			}

			handleMissingFiles();

			// Create the output row meta-data
			data.outputRowMeta = new RowMeta();

			meta.getFields(data.outputRowMeta, getStepname(), null, null, this,
					repository, metaStore);

			// Create convert meta-data objects that will contain Date & Number
			// formatters
			data.convertRowMeta = data.outputRowMeta
					.cloneToType(ValueMetaInterface.TYPE_STRING);
		}
		Object[] r = null;
		try {
			// Grab a row
			r = getOneRow();
			if (r == null) {
				setOutputDone(); // signal end to receiver(s)
				return false; // end of data or error.
			}

			if (log.isRowLevel()) {
				logRowlevel(BaseMessages.getString(PKG,
						"FastJsonInput.Log.ReadRow",
						data.outputRowMeta.getString(r)));
			}
			incrementLinesInput();
			data.rownr++;

			putRow(data.outputRowMeta, r); // copy row to output rowset(s);

			if (meta.getRowLimit() > 0 && data.rownr > meta.getRowLimit()) {
				// limit has been reached: stop now.
				setOutputDone();
				return false;
			}

		} catch (Exception e) {
			boolean sendToErrorRow = false;
			String errorMessage = null;
			if (getStepMeta().isDoingErrorHandling()) {
				sendToErrorRow = true;
				errorMessage = e.toString();
			} else {
				logError(BaseMessages.getString(PKG,
						"FastJsonInput.ErrorInStepRunning", e.getMessage()));
				setErrors(1);
				stopAll();
				setOutputDone(); // signal end to receiver(s)
				return false;
			}
			if (sendToErrorRow) {
				// Simply add this row to the error row
				putError(getInputRowMeta(), r, 1, errorMessage, null,
						"JsonInput001");
			}

		}
		return true;
	}

	private Object[] getOneRow() throws KettleException {

		if (!meta.isInFields()) {
			while ((data.recordnr >= data.nrrecords || data.file == null)) {
				if (!openNextFile()) {
					return null;
				}
			}
		} else {
			while ((data.recordnr >= data.nrrecords || data.readrow == null)) {
				if (!readNextString()) {
					return null;
				}
				if (data.readrow == null) {
					return null;
				}
			}
		}

		return buildRow();
	}

	private Object[] buildRow() throws KettleException {
		// Create new row...
		Object[] outputRowData = null;

		if (data.readrow != null) {
			if (meta.isRemoveSourceField() && data.indexSourceField > -1) {
				// skip the source field in the output array
				int sz = data.readrow.length;
				outputRowData = new Object[sz - 1];
				int ii = 0;
				for (int i = 0; i < sz; i++) {
					if (i != data.indexSourceField) {
						outputRowData[ii++] = data.readrow[i];
					}
				}
			} else {
				outputRowData = data.readrow.clone();
			}
		} else {
			outputRowData = buildEmptyRow();
		}

		// Read fields...
		for (int i = 0; i < data.nrInputFields; i++) {
			// Get field
			FastJsonInputField field = meta.getInputFields()[i];

			// get list of objects for field
			List<Object> ls = data.resultList.get(i).getObjectList();
			String nodevalue = null;
			if (ls != null) {
				Object jo = ls.get( data.recordnr );
				if (jo != null) {
					if (jo instanceof LinkedHashMap) {
						nodevalue = JSONObject.toJSONString((LinkedHashMap) jo);
					} else {
						nodevalue = ls.get(data.recordnr).toString();
					}
				}
			}

			// Do trimming
			switch (field.getTrimType()) {
			case FastJsonInputField.TYPE_TRIM_LEFT:
				nodevalue = Const.ltrim(nodevalue);
				break;
			case FastJsonInputField.TYPE_TRIM_RIGHT:
				nodevalue = Const.rtrim(nodevalue);
				break;
			case FastJsonInputField.TYPE_TRIM_BOTH:
				nodevalue = Const.trim(nodevalue);
				break;
			default:
				break;
			}

			if (meta.isInFields()) {
				// Add result field to input stream
				outputRowData = RowDataUtil.addValueData(outputRowData,
						data.totalpreviousfields + i, nodevalue);
			}
			// Do conversions
			//
			ValueMetaInterface targetValueMeta = data.outputRowMeta
					.getValueMeta(data.totalpreviousfields + i);
			ValueMetaInterface sourceValueMeta = data.convertRowMeta
					.getValueMeta(data.totalpreviousfields + i);
			outputRowData[data.totalpreviousfields + i] = targetValueMeta
					.convertData(sourceValueMeta, nodevalue);

			// Do we need to repeat this field if it is null?
			if (meta.getInputFields()[i].isRepeated()) {
				if (data.previousRow != null && Const.isEmpty(nodevalue)) {
					outputRowData[data.totalpreviousfields + i] = data.previousRow[data.totalpreviousfields
							+ i];
				}
			}
		} // End of loop over fields...

		// When we have an input stream
		// the row index take care of previous fields
		int rowIndex = data.totalpreviousfields + data.nrInputFields;

		// See if we need to add the filename to the row...
		if (meta.includeFilename() && !Const.isEmpty(meta.getFilenameField())) {
			outputRowData[rowIndex++] = data.filename;
		}
		// See if we need to add the row number to the row...
		if (meta.includeRowNumber() && !Const.isEmpty(meta.getRowNumberField())) {
			outputRowData[rowIndex++] = new Long(data.rownr);
		}
		// Possibly add short filename...
		if (meta.getShortFileNameField() != null
				&& meta.getShortFileNameField().length() > 0) {
			outputRowData[rowIndex++] = data.shortFilename;
		}
		// Add Extension
		if (meta.getExtensionField() != null
				&& meta.getExtensionField().length() > 0) {
			outputRowData[rowIndex++] = data.extension;
		}
		// add path
		if (meta.getPathField() != null && meta.getPathField().length() > 0) {
			outputRowData[rowIndex++] = data.path;
		}
		// Add Size
		if (meta.getSizeField() != null && meta.getSizeField().length() > 0) {
			outputRowData[rowIndex++] = new Long(data.size);
		}
		// add Hidden
		if (meta.isHiddenField() != null && meta.isHiddenField().length() > 0) {
			outputRowData[rowIndex++] = new Boolean(data.path);
		}
		// Add modification date
		if (meta.getLastModificationDateField() != null
				&& meta.getLastModificationDateField().length() > 0) {
			outputRowData[rowIndex++] = data.lastModificationDateTime;
		}
		// Add Uri
		if (meta.getUriField() != null && meta.getUriField().length() > 0) {
			outputRowData[rowIndex++] = data.uriName;
		}
		// Add RootUri
		if (meta.getRootUriField() != null
				&& meta.getRootUriField().length() > 0) {
			outputRowData[rowIndex++] = data.rootUriName;
		}
		data.recordnr++;

		RowMetaInterface irow = getInputRowMeta();

		data.previousRow = irow == null ? outputRowData : irow
				.cloneRow(outputRowData); // copy it to make
		// surely the next step doesn't change it in between...

		return outputRowData;
	}

	public boolean init(StepMetaInterface smi, StepDataInterface sdi) {
		meta = (FastJsonInputMeta) smi;
		data = (FastJsonInputData) sdi;

		if (super.init(smi, sdi)) {
			data.rownr = 1L;
			data.nrInputFields = meta.getInputFields().length;
			// Take care of variable substitution
			for (int i = 0; i < data.nrInputFields; i++) {
				FastJsonInputField field = meta.getInputFields()[i];
				field.setPath(environmentSubstitute(field.getPath()));
			}

			try {
				// Init a new JSON reader
				data.jsonReader = new FastJsonReader();
				data.jsonReader
						.setIgnoreMissingPath(meta.isIgnoreMissingPath());
				data.jsonReader.setDefaultPathLeafToNull(meta.isDefaultPathLeafToNull());

			} catch (KettleException e) {
				logError(e.getMessage());
				return false;
			}
			return true;
		}
		return false;
	}

	public void dispose(StepMetaInterface smi, StepDataInterface sdi) {
		meta = (FastJsonInputMeta) smi;
		data = (FastJsonInputData) sdi;
		if (data.file != null) {
			try {
				data.file.close();
			} catch (Exception e) {
				// Ignore errors
			}
		}
		data.resultList = null;
		super.dispose(smi, sdi);
	}
}
