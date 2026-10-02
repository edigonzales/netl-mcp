SELECT kennung,aname,organisation FROM ${targetSchema}.standort
EXCEPT SELECT * FROM (VALUES ('A','Alpha','Synthetic'),('B','Beta','Synthetic')) AS expected(kennung,aname,organisation);
