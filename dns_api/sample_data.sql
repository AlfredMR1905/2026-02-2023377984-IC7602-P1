insert into dns_records (domain, policy, ttl) values
    ('ejemplo.test', 'single', 60),
    ('multi.test', 'multi', 60),
    ('weight.test', 'weight', 60),
    ('geo.test', 'geo', 60)
on conflict (domain) do nothing;

insert into dns_addresses (record_id, address, weight, country)
select id, '10.0.0.25'::inet, 1, null
from dns_records where domain = 'ejemplo.test'
on conflict (record_id, address) do nothing;

insert into dns_addresses (record_id, address, weight, country)
select id, address, 1, null
from dns_records,
     (values ('10.0.0.11'::inet), ('10.0.0.12'::inet)) as sample(address)
where domain = 'multi.test'
on conflict (record_id, address) do nothing;

insert into dns_addresses (record_id, address, weight, country)
select id, address, weight, null
from dns_records,
     (values ('10.0.0.21'::inet, 3), ('10.0.0.22'::inet, 1)) as sample(address, weight)
where domain = 'weight.test'
on conflict (record_id, address) do nothing;

insert into dns_addresses (record_id, address, weight, country)
select id, address, 1, country
from dns_records,
     (values ('10.0.0.31'::inet, 'CR'), ('10.0.0.32'::inet, 'US')) as sample(address, country)
where domain = 'geo.test'
on conflict (record_id, address) do nothing;

insert into ip_country_networks (network, country) values
    ('127.0.0.0/8', 'CR'),
    ('10.0.0.0/8', 'CR'),
    ('192.0.2.0/24', 'US')
on conflict (network) do nothing;
