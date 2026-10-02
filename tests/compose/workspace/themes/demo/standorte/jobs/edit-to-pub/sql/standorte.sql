SELECT s.kennung, s.aname, o.aname AS organisation, s.geometrie
FROM ${sourceSchema}.standorte_standort s
JOIN ${sourceSchema}.standorte_organisation o ON o.t_id = s.organisation
ORDER BY s.kennung;
