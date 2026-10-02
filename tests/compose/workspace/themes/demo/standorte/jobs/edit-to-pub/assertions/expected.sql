WITH expected(kennung,aname,organisation) AS (VALUES ('A','Alpha','Synthetic'),('B','Beta','Synthetic'))
SELECT * FROM expected
EXCEPT SELECT kennung,aname,organisation FROM ${targetSchema}.standort;
