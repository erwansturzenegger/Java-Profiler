package dev.entityprofiler;

import java.util.List;

/**
 * Un événement envoyé au serveur.
 * <p>
 * Types : REQ_START, REQ_END, LOAD, PROXY, COLLECTION, ACCESS, UTIL (sonde "method"), SQL, SESSION, LAZY_ERROR.
 * PROXY, COLLECTION, UTIL, SQL et SESSION sont des "spans" (durée, enfants via {@link #parent}).
 * <p>
 * {@link #inst} et {@link #sess} sont des identités JVM (identityHashCode en hexa) : deux événements sur
 * Customer#12 avec des {@code inst} différents portent sur deux objets Java distincts, ce qui explique qu'une
 * entité initialisée redevienne "lazy" (findById, merge, rechargement dans une autre session...).
 */
final class Event {
    final String type;
    String id;
    String parent;
    String req;
    long t;
    double dur = -1;
    String thread;

    /** Entité concernée (FQCN), ou classe déclarante pour UTIL. */
    String entity;
    /** Identifiant de l'entité (ou clé de la collection). */
    String eid;
    /** Propriété (getter, rôle de collection) ou méthode (UTIL / SQL / SESSION). */
    String prop;

    /** UTIL : entité ciblée (this, ou premier argument entité / proxy / collection) et son id. */
    String tentity;
    String teid;
    /** Identité de l'instance d'entité concernée (propriétaire pour COLLECTION, cible pour UTIL / SESSION). */
    String inst;
    /** Identité de l'instance d'entité retournée (UTIL findById..., SESSION merge), si différente de {@link #inst}. */
    String rinst;
    /** Identité de la session Hibernate (absente si l'objet est détaché ou si elle n'est pas accessible). */
    String sess;
    /**
     * État de l'instance après l'opération (LOAD, PROXY, COLLECTION, UTIL, SESSION), voir {@link Reflect#snapshot}.
     * {@link #rsnap} : état de l'instance retournée quand c'en est une autre (findById, merge).
     */
    List<String> snap;
    List<String> rsnap;
    /**
     * LOAD / PROXY / COLLECTION : pourquoi ce chargement, déduit des spans englobants. {@code genre|libellé} :
     * {@code lazy|Customer.orders} chargement lazy implicite (getter, itération),
     * {@code init|Hibernate.initialize › Customer.orders} lazy demandé explicitement (sonde de rôle "load"),
     * {@code explicit|CustomerRepository.findById} ou {@code explicit|session.refresh} chargement demandé,
     * {@code query|from Customer p where ...} requête HQL / JPQL / Criteria / native,
     * {@code other|} aucun span englobant (chemin non instrumenté).
     */
    String cause;

    /** Premier frame applicatif "métier" (packages appelants, hors exclusions), ou [EL]/[JSF] si déclenché par la vue. */
    String caller;
    /** Premier frame applicatif sans tenir compte des exclusions (ex. OrderService.load:182). */
    String inner;
    /** Méthode appelée juste avant d'entrer dans l'appelant (ex. PersistentSet.iterator, Customer(proxy).getName, Hibernate.initialize). */
    String trigger;
    /** Phase JSF déduite de la pile. */
    String phase;
    /** Expression EL en cours d'évaluation. */
    String el;

    /** Détails libres : SQL, arguments, taille de collection... */
    String info;
    /** ACCESS : état de la valeur retournée (LAZY_COLLECTION, LAZY_PROXY, UNINITIALIZED). */
    String state;
    String error;
    List<String> stack;

    /** Sonde à l'origine de l'événement (profil/sonde), pour les sondes "method". */
    String probe;
    /** Libellé de la sonde (remplace Classe.méthode à l'affichage). */
    String label;
    /** Tags de la sonde. */
    List<String> tags;
    /** Tags hérités des spans englobants dont la sonde les propage (ex. tout ce qui se passe sous BatchJob.run). */
    List<String> scope;
    /** Non sérialisé : les tags de cet événement s'appliquent à ses enfants. */
    transient boolean propagate;

    Event(String type) {
        this.type = type;
    }

    void toJson(StringBuilder sb) {
        sb.append("{\"type\":\"").append(type).append('"');
        Json.field(sb, "id", id);
        Json.field(sb, "parent", parent);
        Json.field(sb, "req", req);
        sb.append(",\"t\":").append(t);
        if (dur >= 0)
            sb.append(",\"dur\":").append(Math.round(dur * 1000) / 1000.0);
        Json.field(sb, "thread", thread);
        Json.field(sb, "entity", entity);
        Json.field(sb, "eid", eid);
        Json.field(sb, "prop", prop);
        Json.field(sb, "tentity", tentity);
        Json.field(sb, "teid", teid);
        Json.field(sb, "inst", inst);
        Json.field(sb, "rinst", rinst);
        Json.field(sb, "sess", sess);
        Json.field(sb, "snap", snap);
        Json.field(sb, "rsnap", rsnap);
        Json.field(sb, "cause", cause);
        Json.field(sb, "caller", caller);
        Json.field(sb, "inner", inner);
        Json.field(sb, "trigger", trigger);
        Json.field(sb, "phase", phase);
        Json.field(sb, "el", el);
        Json.field(sb, "info", info);
        Json.field(sb, "state", state);
        Json.field(sb, "error", error);
        Json.field(sb, "stack", stack);
        Json.field(sb, "probe", probe);
        Json.field(sb, "label", label);
        if (tags != null && !tags.isEmpty())
            Json.field(sb, "tags", tags);
        if (scope != null && !scope.isEmpty())
            Json.field(sb, "scope", scope);
        sb.append('}');
    }
}
