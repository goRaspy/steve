package de.rwth.idsg.steve.repository;

import de.rwth.idsg.steve.ocpp.OcppProtocol;
import de.rwth.idsg.steve.utils.DateTimeUtils;
import lombok.extern.slf4j.Slf4j;
import ocpp.cs._2012._06.Location;
import ocpp.cs._2012._06.Measurand;
import ocpp.cs._2012._06.MeterValue;
import ocpp.cs._2012._06.ReadingContext;
import ocpp.cs._2012._06.UnitOfMeasure;
import ocpp.cs._2012._06.ValueFormat;
import org.jooq.BatchBindStep;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.TransactionalCallable;
import org.jooq.TransactionalRunnable;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

import static jooq.steve.db.tables.Chargebox.CHARGEBOX;
import static jooq.steve.db.tables.Connector.CONNECTOR;
import static jooq.steve.db.tables.ConnectorMetervalue.CONNECTOR_METERVALUE;
import static jooq.steve.db.tables.ConnectorStatus.CONNECTOR_STATUS;
import static jooq.steve.db.tables.Transaction.TRANSACTION;

/**
 * This class has methods for database access that are used by the OCPP service.
 *
 * http://www.jooq.org/doc/3.4/manual/sql-execution/transaction-management/
 * 
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 *  
 */
@Slf4j
@Repository
public class OcppServerRepositoryImpl implements OcppServerRepository {

    @Autowired
    @Qualifier("jooqConfig")
    private Configuration config;

    @Autowired private ReservationRepository reservationRepository;

    /**
     * UPDATE chargebox
     * SET endpoint_address = ?,
     *     ocppProtocol = ?,
     *     chargePointVendor = ?,
     *     chargePointModel = ?,
     *     chargePointSerialNumber = ?,
     *     chargeBoxSerialNumber = ?,
     *     fwVersion = ?,
     *     iccid = ?,
     *     imsi = ?,
     *     meterType = ?,
     *     meterSerialNumber = ?,
     *     lastHeartbeatTimestamp = ?
     * WHERE chargeBoxId = ?
     */
    @Override
    public boolean updateChargebox(String endpointAddress, OcppProtocol protocol, String vendor, String model,
                                   String pointSerial, String boxSerial, String fwVersion, String iccid, String imsi,
                                   String meterType, String meterSerial, String chargeBoxIdentity, Timestamp now) {

        int count = DSL.using(config)
                       .update(CHARGEBOX)
                       .set(CHARGEBOX.ENDPOINT_ADDRESS, endpointAddress)
                       .set(CHARGEBOX.OCPPPROTOCOL, protocol.getCompositeValue())
                       .set(CHARGEBOX.CHARGEPOINTVENDOR, vendor)
                       .set(CHARGEBOX.CHARGEPOINTMODEL, model)
                       .set(CHARGEBOX.CHARGEPOINTSERIALNUMBER, pointSerial)
                       .set(CHARGEBOX.CHARGEBOXSERIALNUMBER, boxSerial)
                       .set(CHARGEBOX.FWVERSION, fwVersion)
                       .set(CHARGEBOX.ICCID, iccid)
                       .set(CHARGEBOX.IMSI, imsi)
                       .set(CHARGEBOX.METERTYPE, meterType)
                       .set(CHARGEBOX.METERSERIALNUMBER, meterSerial)
                       .set(CHARGEBOX.LASTHEARTBEATTIMESTAMP, now)
                       .where(CHARGEBOX.CHARGEBOXID.equal(chargeBoxIdentity))
                       .execute();

        boolean isRegistered = false;

        if (count == 1) {
            log.info("The chargebox '{}' is registered and its boot acknowledged.", chargeBoxIdentity);
            isRegistered = true;
        } else {
            log.error("The chargebox '{}' is NOT registered and its boot NOT acknowledged.", chargeBoxIdentity);
        }
        return isRegistered;
    }

    /**
     * UPDATE chargebox
     * SET fwUpdateStatus = ?, fwUpdateTimestamp = ?
     * WHERE chargeBoxId = ?
     */
    @Override
    public void updateChargeboxFirmwareStatus(String chargeBoxIdentity, String firmwareStatus) {
        DSL.using(config)
           .update(CHARGEBOX)
           .set(CHARGEBOX.FWUPDATESTATUS, firmwareStatus)
           .set(CHARGEBOX.FWUPDATETIMESTAMP, DateTimeUtils.getCurrentTimestamp())
           .where(CHARGEBOX.CHARGEBOXID.equal(chargeBoxIdentity))
           .execute();
    }

    /**
     * UPDATE chargebox
     * SET diagnosticsStatus = ?, diagnosticsTimestamp = ?
     * WHERE chargeBoxId = ?
     */
    @Override
    public void updateChargeboxDiagnosticsStatus(String chargeBoxIdentity, String status) {
        DSL.using(config)
           .update(CHARGEBOX)
           .set(CHARGEBOX.DIAGNOSTICSSTATUS, status)
           .set(CHARGEBOX.DIAGNOSTICSTIMESTAMP, DateTimeUtils.getCurrentTimestamp())
           .where(CHARGEBOX.CHARGEBOXID.equal(chargeBoxIdentity))
           .execute();
    }

    /**
     * UPDATE chargebox
     * SET lastHeartbeatTimestamp = ?
     * WHERE chargeBoxId = ?
     */
    @Override
    public void updateChargeboxHeartbeat(String chargeBoxIdentity, Timestamp ts) {
        DSL.using(config)
           .update(CHARGEBOX)
           .set(CHARGEBOX.LASTHEARTBEATTIMESTAMP, ts)
           .where(CHARGEBOX.CHARGEBOXID.equal(chargeBoxIdentity))
           .execute();
    }

    @Override
    public void insertConnectorStatus12(String chargeBoxIdentity, int connectorId, String status, Timestamp timestamp,
                                        String errorCode) {
        // Delegate
        this.insertConnectorStatus15(chargeBoxIdentity, connectorId, status, timestamp, errorCode, null, null, null);
    }

    @Override
    public void insertConnectorStatus15(final String chargeBoxIdentity, final int connectorId, final String status,
                                        final Timestamp timestamp,
                                        final String errorCode, final String errorInfo,
                                        final String vendorId, final String vendorErrorCode) {

        DSL.using(config).transaction(new TransactionalRunnable() {
            @Override
            public void run(Configuration configuration) throws Exception {
                DSLContext ctx = DSL.using(configuration);

                // Step 1
                insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);

                // -------------------------------------------------------------------------
                // Step 2: We store a log of connector statuses
                // -------------------------------------------------------------------------

                /**
                 * INSERT INTO connector_status
                 *  (connector_pk,
                 *  statusTimestamp,
                 *  status,
                 *  errorCode,
                 *  errorInfo,
                 *  vendorId,
                 *  vendorErrorCode)
                 * VALUES
                 * ((SELECT connector_pk FROM connector WHERE chargeBoxId = ? AND connectorId = ?) , ? , ? , ? , ? , ? , ?)
                 */
                ctx.insertInto(CONNECTOR_STATUS)
                   .set(CONNECTOR_STATUS.CONNECTOR_PK, DSL.select(CONNECTOR.CONNECTOR_PK)
                                                          .from(CONNECTOR)
                                                          .where(CONNECTOR.CHARGEBOXID.equal(chargeBoxIdentity))
                                                          .and(CONNECTOR.CONNECTORID.equal(connectorId))
                   )
                   .set(CONNECTOR_STATUS.STATUSTIMESTAMP, timestamp)
                   .set(CONNECTOR_STATUS.STATUS, status)
                   .set(CONNECTOR_STATUS.ERRORCODE, errorCode)
                   .set(CONNECTOR_STATUS.ERRORINFO, errorInfo)
                   .set(CONNECTOR_STATUS.VENDORID, vendorId)
                   .set(CONNECTOR_STATUS.VENDORERRORCODE, vendorErrorCode)
                   .execute();

                log.debug("Stored a new connector status for {}/{}.", chargeBoxIdentity, connectorId);
            }
        });
    }

    @Override
    public void insertMeterValues12(final String chargeBoxIdentity, final int connectorId,
                                    final List<ocpp.cs._2010._08.MeterValue> list) {

        DSL.using(config).transaction(new TransactionalRunnable() {
            @Override
            public void run(Configuration configuration) throws Exception {
                DSLContext ctx = DSL.using(configuration);

                insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);
                int connectorPk = getConnectorPkFromConnector(ctx, chargeBoxIdentity, connectorId);
                batchInsertMeterValues12(ctx, list, connectorPk);
            }
        });
    }

    @Override
    public void insertMeterValues15(final String chargeBoxIdentity, final int connectorId,
                                    final List<ocpp.cs._2012._06.MeterValue> list, final Integer transactionId) {

        DSL.using(config).transaction(new TransactionalRunnable() {
            @Override
            public void run(Configuration configuration) throws Exception {
                DSLContext ctx = DSL.using(configuration);

                insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);
                int connectorPk = getConnectorPkFromConnector(ctx, chargeBoxIdentity, connectorId);
                batchInsertMeterValues15(ctx, list, connectorPk, transactionId);
            }
        });
    }

    @Override
    public void insertMeterValuesOfTransaction(String chargeBoxIdentity, final int transactionId, final List<MeterValue> list) {

        DSL.using(config).transaction(new TransactionalRunnable() {
            @Override
            public void run(Configuration configuration) throws Exception {
                DSLContext ctx = DSL.using(configuration);

                // First, get connector primary key from transaction table
                int connectorPk = ctx.select(TRANSACTION.CONNECTOR_PK)
                                     .from(TRANSACTION)
                                     .where(TRANSACTION.TRANSACTION_PK.equal(transactionId))
                                     .fetchOne()
                                     .value1();

                batchInsertMeterValues15(ctx, list, connectorPk, transactionId);
            }
        });
    }

    @Override
    public Integer insertTransaction12(String chargeBoxIdentity, int connectorId, String idTag,
                                       Timestamp startTimestamp, String startMeterValue) {
        // Delegate
        return this.insertTransaction15(chargeBoxIdentity, connectorId, idTag, startTimestamp, startMeterValue, null);
    }

    @Override
    public Integer insertTransaction15(final String chargeBoxIdentity, final int connectorId, final String idTag,
                                       final Timestamp startTimestamp, final String startMeterValue,
                                       final Integer reservationId) {

        return DSL.using(config).transactionResult(new TransactionalCallable<Integer>() {
            @Override
            public Integer run(Configuration configuration) throws Exception {
                DSLContext ctx = DSL.using(configuration);

                insertIgnoreConnector(ctx, chargeBoxIdentity, connectorId);

                // -------------------------------------------------------------------------
                // Step 1: Insert transaction
                // -------------------------------------------------------------------------

                /**
                 * INSERT INTO transaction
                 * (connector_pk, idTag, startTimestamp, startValue)
                 * VALUES
                 * ((SELECT connector_pk FROM connector WHERE chargeBoxId = ? AND connectorId = ?) , ? , ? , ?)
                 */
                int transactionId = ctx.insertInto(TRANSACTION)
                                       .set(CONNECTOR_STATUS.CONNECTOR_PK, DSL.select(CONNECTOR.CONNECTOR_PK)
                                                                              .from(CONNECTOR)
                                                                              .where(CONNECTOR.CHARGEBOXID.equal(chargeBoxIdentity))
                                                                              .and(CONNECTOR.CONNECTORID.equal(connectorId))
                                       )
                                       .set(TRANSACTION.IDTAG, idTag)
                                       .set(TRANSACTION.STARTTIMESTAMP, startTimestamp)
                                       .set(TRANSACTION.STARTVALUE, startMeterValue)
                                       .returning(TRANSACTION.TRANSACTION_PK)
                                       .fetchOne()
                                       .getTransactionPk();

                // -------------------------------------------------------------------------
                // Step 2 for OCPP 1.5: A startTransaction may be related to a reservation
                // -------------------------------------------------------------------------

                if (reservationId != null) {
                    reservationRepository.used(reservationId, transactionId);
                }

                return transactionId;
            }
        });
    }

    /**
     * UPDATE transaction
     * SET stopTimestamp = ?, stopValue = ?
     * WHERE transaction_pk = ?
     * AND stopTimestamp IS NULL
     * AND stopVALUE IS NULL
     *
     * After update, a DB trigger sets the user.inTransaction field to 0
     */
    @Override
    public void updateTransaction(int transactionId, Timestamp stopTimestamp, String stopMeterValue) {
        DSL.using(config)
           .update(TRANSACTION)
           .set(TRANSACTION.STOPTIMESTAMP, stopTimestamp)
           .set(TRANSACTION.STOPVALUE, stopMeterValue)
           .where(TRANSACTION.TRANSACTION_PK.equal(transactionId))
                .and(TRANSACTION.STOPTIMESTAMP.isNull())
                .and(TRANSACTION.STOPVALUE.isNull())
           .execute();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * If the connector information was not received before, insert it. Otherwise, ignore.
     *
     * INSERT IGNORE INTO connector (chargeBoxId, connectorId) VALUES (?,?)
     */
    private void insertIgnoreConnector(DSLContext ctx, String chargeBoxIdentity, int connectorId) {
        int count = ctx.insertInto(CONNECTOR,
                            CONNECTOR.CHARGEBOXID, CONNECTOR.CONNECTORID)
                       .values(chargeBoxIdentity, connectorId)
                       .onDuplicateKeyIgnore() // Important detail
                       .execute();

        if (count == 1) {
            log.info("The connector {}/{} is NEW, and inserted into DB.", chargeBoxIdentity, connectorId);
        }
    }

    /**
     * SELECT connector_pk
     * FROM connector
     * WHERE chargeBoxId = ? AND connectorId = ?
     */
    private int getConnectorPkFromConnector(DSLContext ctx, String chargeBoxIdentity, int connectorId) {
        return ctx.select(CONNECTOR.CONNECTOR_PK)
                  .from(CONNECTOR)
                  .where(CONNECTOR.CHARGEBOXID.equal(chargeBoxIdentity)
                    .and(CONNECTOR.CONNECTORID.equal(connectorId)))
                  .fetchOne()
                  .value1();
    }

    /**
     * INSERT INTO connector_metervalue
     * (connector_pk, valueTimestamp, value)
     * VALUES (?,?,?)
     */
    private void batchInsertMeterValues12(DSLContext ctx, List<ocpp.cs._2010._08.MeterValue> list, int connectorPk) {
        // Init query with DUMMY values. The actual values are not important.
        BatchBindStep batchBindStep = ctx.batch(
                ctx.insertInto(CONNECTOR_METERVALUE,
                        CONNECTOR_METERVALUE.CONNECTOR_PK,
                        CONNECTOR_METERVALUE.VALUETIMESTAMP,
                        CONNECTOR_METERVALUE.VALUE)
                   .values(0, null, null)
        );

        // OCPP 1.2 allows multiple "values" elements
        for (ocpp.cs._2010._08.MeterValue valuesElement : list) {
            Timestamp ts = new Timestamp(valuesElement.getTimestamp().getMillis());
            String value = String.valueOf(valuesElement.getValue());

            batchBindStep.bind(connectorPk, ts, value);
        }

        batchBindStep.execute();
    }

    /**
     * INSERT INTO connector_metervalue
     * (connector_pk, transaction_pk, valueTimestamp, value, readingContext, format, measurand, location, unit)
     * VALUES (?,?,?,?,?,?,?,?,?)
     */
    private void batchInsertMeterValues15(DSLContext ctx, List<ocpp.cs._2012._06.MeterValue> list, int connectorPk,
                                          Integer transactionId) {
        // Init query with DUMMY values. The actual values are not important.
        BatchBindStep batchBindStep = ctx.batch(
                ctx.insertInto(CONNECTOR_METERVALUE,
                        CONNECTOR_METERVALUE.CONNECTOR_PK,
                        CONNECTOR_METERVALUE.TRANSACTION_PK,
                        CONNECTOR_METERVALUE.VALUETIMESTAMP,
                        CONNECTOR_METERVALUE.VALUE,
                        CONNECTOR_METERVALUE.READINGCONTEXT,
                        CONNECTOR_METERVALUE.FORMAT,
                        CONNECTOR_METERVALUE.MEASURAND,
                        CONNECTOR_METERVALUE.LOCATION,
                        CONNECTOR_METERVALUE.UNIT)
                   .values(0, null, null, null, null, null, null, null, null)
        );

        // OCPP 1.5 allows multiple "values" elements
        for (MeterValue valuesElement : list) {
            Timestamp timestamp = new Timestamp(valuesElement.getTimestamp().getMillis());

            // OCPP 1.5 allows multiple "value" elements under each "values" element.
            List<MeterValue.Value> valueList = valuesElement.getValue();
            for (MeterValue.Value valueElement : valueList) {

                ReadingContext context = valueElement.getContext();
                ValueFormat format = valueElement.getFormat();
                Measurand measurand = valueElement.getMeasurand();
                Location location = valueElement.getLocation();
                UnitOfMeasure unit = valueElement.getUnit();

                // OCPP 1.5 allows for each "value" element to have optional attributes
                String contextValue     = (context == null)   ? null : context.value();
                String formatValue      = (format == null)    ? null : format.value();
                String measurandValue   = (measurand == null) ? null : measurand.value();
                String locationValue    = (location == null)  ? null : location.value();
                String unitValue        = (unit == null)      ? null : unit.value();

                batchBindStep.bind(connectorPk, transactionId, timestamp, valueElement.getValue(),
                                   contextValue, formatValue, measurandValue, locationValue, unitValue);
            }
        }

        batchBindStep.execute();
    }
}