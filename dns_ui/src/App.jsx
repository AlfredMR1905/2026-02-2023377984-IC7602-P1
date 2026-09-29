import { useEffect, useState } from "react";
import {
  createDomain, createNetwork, deleteDomain, deleteNetwork,
  listDomains, listNetworks, updateDomain, updateNetwork,
} from "./api.js";

const blankDomain = () => ({
  id: null,
  domain: "",
  policy: "single",
  ttl: 60,
  addresses: [{ address: "", weight: 1, country: "" }],
});
const blankNetwork = () => ({ id: null, network: "", country: "" });

const policyNames = {
  single: "Una IP",
  multi: "Round-robin",
  weight: "Por peso",
  geo: "Por país",
};

export default function App() {
  const [domains, setDomains] = useState([]);
  const [networks, setNetworks] = useState([]);
  const [domainForm, setDomainForm] = useState(blankDomain);
  const [networkForm, setNetworkForm] = useState(blankNetwork);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function load() {
    const [nextDomains, nextNetworks] = await Promise.all([listDomains(), listNetworks()]);
    setDomains(nextDomains);
    setNetworks(nextNetworks);
  }

  useEffect(() => {
    load().catch((reason) => setError(`No se pudieron cargar los datos. ${reason.message}`));
  }, []);

  async function run(action, success) {
    setBusy(true);
    setError("");
    setMessage("");
    try {
      await action();
      await load();
      setMessage(success);
    } catch (reason) {
      setError(reason.message);
    } finally {
      setBusy(false);
    }
  }

  function changeAddress(index, field, value) {
    setDomainForm((current) => ({
      ...current,
      addresses: current.addresses.map((item, position) =>
        position === index ? { ...item, [field]: value } : item,
      ),
    }));
  }

  function saveDomain(event) {
    event.preventDefault();
    const data = {
      domain: domainForm.domain.trim().toLowerCase(),
      policy: domainForm.policy,
      ttl: Number(domainForm.ttl),
      addresses: domainForm.addresses.map((item) => ({
        address: item.address.trim(),
        weight: Number(item.weight),
        country: item.country.trim().toUpperCase() || null,
      })),
    };
    run(async () => {
      if (domainForm.id === null) {
        await createDomain(data);
      } else {
        await updateDomain(domainForm.id, data);
      }
      setDomainForm(blankDomain());
    }, "Registro DNS guardado.");
  }

  function editDomain(domain) {
    setDomainForm({
      ...domain,
      addresses: domain.addresses.map((item) => ({ ...item, country: item.country || "" })),
    });
    document.getElementById("domain-form")?.scrollIntoView({ behavior: "smooth" });
  }

  function removeDomain(domain) {
    if (!window.confirm(`¿Borrar ${domain.domain}?`)) return;
    run(async () => {
      await deleteDomain(domain.id);
      if (domainForm.id === domain.id) setDomainForm(blankDomain());
    }, "Registro DNS borrado.");
  }

  function saveNetwork(event) {
    event.preventDefault();
    const data = {
      network: networkForm.network.trim(),
      country: networkForm.country.trim().toUpperCase(),
    };
    run(async () => {
      if (networkForm.id === null) {
        await createNetwork(data);
      } else {
        await updateNetwork(networkForm.id, data);
      }
      setNetworkForm(blankNetwork());
    }, "Red IP-país guardada.");
  }

  function removeNetwork(network) {
    if (!window.confirm(`¿Borrar ${network.network}?`)) return;
    run(async () => {
      await deleteNetwork(network.id);
      if (networkForm.id === network.id) setNetworkForm(blankNetwork());
    }, "Red IP-país borrada.");
  }

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Proyecto de Redes · IC7602</p>
          <h1>Administración DNS</h1>
          <p className="subtitle">Registros y redes IP-país almacenados en Supabase.</p>
        </div>
        <span className="status">Supabase vía API</span>
      </header>

      {error && <p className="alert error" role="alert">{error}</p>}
      {message && <p className="alert success" role="status">{message}</p>}

      <section className="section-heading">
        <div>
          <p className="eyebrow">01 / Dominios</p>
          <h2>Registros DNS</h2>
        </div>
        <span className="count">{domains.length} configurados</span>
      </section>

      <div className="columns">
        <form id="domain-form" className="card form-card" onSubmit={saveDomain}>
          <h3>{domainForm.id === null ? "Nuevo registro" : "Editar registro"}</h3>
          <label>Dominio
            <input required placeholder="ejemplo.test" value={domainForm.domain}
              onChange={(event) => setDomainForm({ ...domainForm, domain: event.target.value })} />
          </label>
          <div className="field-row">
            <label>Política
              <select value={domainForm.policy} onChange={(event) => setDomainForm({
                ...domainForm,
                policy: event.target.value,
                addresses: event.target.value === "single" ? domainForm.addresses.slice(0, 1) : domainForm.addresses,
              })}>
                {Object.entries(policyNames).map(([value, name]) => <option key={value} value={value}>{name}</option>)}
              </select>
            </label>
            <label>TTL (segundos)
              <input required type="number" min="1" value={domainForm.ttl}
                onChange={(event) => setDomainForm({ ...domainForm, ttl: event.target.value })} />
            </label>
          </div>
          <div className="address-heading">
            <strong>Direcciones IPv4</strong>
            <button className="text-button" type="button" disabled={busy || domainForm.policy === "single"}
              onClick={() => setDomainForm({
                ...domainForm,
                addresses: [...domainForm.addresses, { address: "", weight: 1, country: "" }],
              })}>+ Agregar IP</button>
          </div>
          {domainForm.addresses.map((item, index) => (
            <div className="address-row" key={index}>
              <label>IPv4
                <input required placeholder="10.0.0.25" value={item.address}
                  onChange={(event) => changeAddress(index, "address", event.target.value)} />
              </label>
              <label>Peso
                <input required type="number" min="1" value={item.weight}
                  onChange={(event) => changeAddress(index, "weight", event.target.value)} />
              </label>
              <label>País
                <input placeholder="CR" maxLength="2" value={item.country}
                  onChange={(event) => changeAddress(index, "country", event.target.value)} />
              </label>
              <button className="icon-button" type="button" aria-label={`Quitar IP ${index + 1}`}
                disabled={busy || domainForm.addresses.length === 1}
                onClick={() => setDomainForm({
                  ...domainForm,
                  addresses: domainForm.addresses.filter((_, position) => position !== index),
                })}>×</button>
            </div>
          ))}
          <p className="hint">Peso se usa en «Por peso»; país se usa en «Por país».</p>
          <div className="actions">
            <button className="primary" disabled={busy} type="submit">{busy ? "Guardando…" : "Guardar registro"}</button>
            {domainForm.id !== null && <button type="button" className="secondary" onClick={() => setDomainForm(blankDomain())}>Cancelar</button>}
          </div>
        </form>

        <div className="card list-card">
          <h3>Dominios existentes</h3>
          {domains.length === 0 && <p className="empty">Aún no hay registros configurados.</p>}
          {domains.map((domain) => (
            <article className="record" key={domain.id}>
              <div className="record-top">
                <div><strong>{domain.domain}</strong><p>{policyNames[domain.policy] || domain.policy} · TTL {domain.ttl}s</p></div>
                <div className="record-actions">
                  <button type="button" disabled={busy} onClick={() => editDomain(domain)}>Editar</button>
                  <button type="button" disabled={busy} className="danger" onClick={() => removeDomain(domain)}>Borrar</button>
                </div>
              </div>
              <div className="chips">{domain.addresses.map((item, index) => (
                <span className="chip" key={index}>{item.address}{domain.policy === "weight" ? ` · peso ${item.weight}` : ""}{item.country ? ` · ${item.country}` : ""}</span>
              ))}</div>
            </article>
          ))}
        </div>
      </div>

      <section className="section-heading network-heading">
        <div>
          <p className="eyebrow">02 / Geolocalización</p>
          <h2>Redes IP-país</h2>
        </div>
        <span className="count">{networks.length} redes</span>
      </section>

      <div className="columns">
        <form className="card form-card" onSubmit={saveNetwork}>
          <h3>{networkForm.id === null ? "Nueva red" : "Editar red"}</h3>
          <div className="field-row">
            <label>Red CIDR
              <input required placeholder="192.0.2.0/24" value={networkForm.network}
                onChange={(event) => setNetworkForm({ ...networkForm, network: event.target.value })} />
            </label>
            <label>País
              <input required placeholder="CR" maxLength="2" value={networkForm.country}
                onChange={(event) => setNetworkForm({ ...networkForm, country: event.target.value })} />
            </label>
          </div>
          <div className="actions">
            <button className="primary" disabled={busy} type="submit">{busy ? "Guardando…" : "Guardar red"}</button>
            {networkForm.id !== null && <button type="button" className="secondary" onClick={() => setNetworkForm(blankNetwork())}>Cancelar</button>}
          </div>
        </form>

        <div className="card list-card">
          <h3>Redes configuradas</h3>
          {networks.length === 0 && <p className="empty">Aún no hay redes configuradas.</p>}
          {networks.map((network) => (
            <article className="record network-record" key={network.id}>
              <div><strong>{network.network}</strong><p>País: {network.country}</p></div>
              <div className="record-actions">
                <button type="button" disabled={busy} onClick={() => setNetworkForm(network)}>Editar</button>
                <button type="button" disabled={busy} className="danger" onClick={() => removeNetwork(network)}>Borrar</button>
              </div>
            </article>
          ))}
        </div>
      </div>
    </main>
  );
}
