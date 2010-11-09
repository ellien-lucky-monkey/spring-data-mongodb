/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.datastore.document.mongodb;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.datastore.document.UncategorizedDocumentStoreException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import com.mongodb.DB;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.MongoException.DuplicateKey;
import com.mongodb.MongoException.Network;

/**
 * Helper class featuring helper methods for internal MongoDb classes.
 *
 * <p>Mainly intended for internal use within the framework.
 *
 * @author Thomas Risberg
 * @author Graeme Rocher
 * 
 * @since 1.0
 */
public class MongoDbUtils {

	static final Log logger = LogFactory.getLog(MongoDbUtils.class);
	
	/**
	 * Convert the given runtime exception to an appropriate exception from the
	 * <code>org.springframework.dao</code> hierarchy.
	 * Return null if no translation is appropriate: any other exception may
	 * have resulted from user code, and should not be translated.
	 * @param ex runtime exception that occurred
	 * @return the corresponding DataAccessException instance,
	 * or <code>null</code> if the exception should not be translated
	 */
	public static DataAccessException translateMongoExceptionIfPossible(RuntimeException ex) {

		// Check for well-known MongoException subclasses.
		
		// All other MongoExceptions
		if(ex instanceof DuplicateKey) {
			return new DataIntegrityViolationException(ex.getMessage(),ex);
		}
		if(ex instanceof Network) {
			return new DataAccessResourceFailureException(ex.getMessage(), ex);
		}
		if (ex instanceof MongoException) {
			return new UncategorizedDocumentStoreException(ex.getMessage(), ex);
		}
		
		// If we get here, we have an exception that resulted from user code,
		// rather than the persistence provider, so we return null to indicate
		// that translation should not occur.
		return null;				
	}

	public static DB getDB(Mongo mongo, String databaseName) {
		return doGetDB(mongo, databaseName, true);
	}

	public static DB doGetDB(Mongo mongo, String databaseName, boolean allowCreate) {
		Assert.notNull(mongo, "No Mongo instance specified");

		DBHolder dbHolder = (DBHolder) TransactionSynchronizationManager.getResource(mongo);
		if (dbHolder != null && !dbHolder.isEmpty()) {
			// pre-bound Mongo DB 
			DB db = null;
			if (TransactionSynchronizationManager.isSynchronizationActive() &&
					dbHolder.doesNotHoldNonDefaultDB()) {
				// Spring transaction management is active ->
				db = dbHolder.getDB();
				if (db != null && !dbHolder.isSynchronizedWithTransaction()) {
					logger.debug("Registering Spring transaction synchronization for existing Mongo DB");
					TransactionSynchronizationManager.registerSynchronization(new MongoSynchronization(dbHolder, mongo));
					dbHolder.setSynchronizedWithTransaction(true);
				}
			}
			if (db != null) {
				return db;
			}
		}

		logger.debug("Opening Mongo DB");
		DB db = mongo.getDB(databaseName);
		// Use same Session for further Mongo actions within the transaction.
		// Thread object will get removed by synchronization at transaction completion.
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			// We're within a Spring-managed transaction, possibly from JtaTransactionManager.
			logger.debug("Registering Spring transaction synchronization for new Hibernate Session");
			DBHolder holderToUse = dbHolder;
			if (holderToUse == null) {
				holderToUse = new DBHolder(db);
			}
			else {
				holderToUse.addDB(db);
			}
			TransactionSynchronizationManager.registerSynchronization(new MongoSynchronization(holderToUse, mongo));
			holderToUse.setSynchronizedWithTransaction(true);
			if (holderToUse != dbHolder) {
				TransactionSynchronizationManager.bindResource(mongo, holderToUse);
			}
		}

		// Check whether we are allowed to return the DB.
		if (!allowCreate && !isDBTransactional(db, mongo)) {
			throw new IllegalStateException("No Mongo DB bound to thread, " +
			    "and configuration does not allow creation of non-transactional one here");
		}

		return db;
	}
	

	/**
	 * Return whether the given DB instance is transactional, that is,
	 * bound to the current thread by Spring's transaction facilities.
	 * @param db the DB to check
	 * @param mongo the Mongo instance that the DB was created with
	 * (may be <code>null</code>)
	 * @return whether the DB is transactional
	 */
	public static boolean isDBTransactional(DB db, Mongo mongo) {
		if (mongo == null) {
			return false;
		}
		DBHolder dbHolder =
				(DBHolder) TransactionSynchronizationManager.getResource(mongo);
		return (dbHolder != null && dbHolder.containsDB(db));
	}
	
	/**
	 * Perform actual closing of the Mongo DB object,
	 * catching and logging any cleanup exceptions thrown.
	 * @param db the DB to close (may be <code>null</code>)
	 */
	public static void closeDB(DB db) {
		if (db != null) {
			logger.debug("Closing Mongo DB object");
			try {
				db.requestDone();
			}
			catch (Throwable ex) {
				logger.debug("Unexpected exception on closing Mongo DB object", ex);
			}
		}
	}	
}
