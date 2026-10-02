INSERT INTO ${sourceSchema}.standorte_organisation(t_id, aname) VALUES (10, 'Synthetic');
INSERT INTO ${sourceSchema}.standorte_standort(t_id, kennung, aname, organisation, geometrie)
VALUES (20, 'A', 'Alpha', 10, ST_SetSRID(ST_MakePoint(2600000,1200000),2056)),
       (21, 'B', 'Beta', 10, ST_SetSRID(ST_MakePoint(2600010,1200010),2056));
