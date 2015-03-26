package com.formulasearchengine.mathosphere.basex.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.formulasearchengine.mathosphere.basex.Client;
import com.formulasearchengine.mathosphere.basex.XMLHelper;
import org.w3c.dom.Document;

/**
 * Created by Moritz on 14.03.2015.
 */
public class MathUpdate {
	public MathUpdate (Integer[] delete, String harvest) {
		this.delete = delete;
		this.harvest = harvest;
	}

	public MathUpdate () {
	}

	public String getResponse () {
		return response;
	}

	public void setResponse (String response) {
		this.response = response;
	}

	public Integer[] getDelete () {
		return delete;
	}

	public void setDelete (Integer[] delete) {
		this.delete = delete;
	}

	public String getHarvest () {
		return harvest;
	}

	public void setHarvest (String harvest) {
		this.harvest = harvest;
	}

	Integer[] delete = {};
	String harvest = "";
	String response = "";
	boolean success = false;

	@JsonIgnore
	public MathUpdate run () {
		Client client = new Client();
		client.setShowTime( false ); //for testing
		if ( harvest.length()>0 ){
			Document doc = XMLHelper.String2Doc( harvest );
			//TODO: validate document
			if ( doc != null && client.updateFormula( doc.getDocumentElement() ) ){
				this.response = "updated";
				success = true;
			} else {
				this.response = "update failed";
			}
		}
		if (delete.length>0){
			for ( Integer s : delete ) {
				if ( client.deleteRevisionFormula(s) ) {
					response += "\nrevision " + s + " deleted";
					success &= true;
				} else {
					response += "\nrevision " + s + " not deleted";
				}
			}
		}
		return this;
	}
	public boolean isSuccess () {
		return success;
	}
}
