# Releasing to Maven Central

The root pom has an inactive `release` profile: sources and javadoc jars, GPG signing, and the Central Publisher plugin. It is verified locally with `mvn -B -P release verify -Dgpg.skip=true`. The steps below need accounts and keys that only the project owner can create.

## 1. Choose the namespace

Central only accepts a groupId whose namespace you can prove.

- Keep `in.jolt` only if you own the domain `jolt.in` (verified by a DNS TXT record).
- Otherwise use `io.github.thiru-1074`, verified by creating a temporary public repo named as Central asks.

Switching namespace means renaming the groupId:

1. Replace `<groupId>in.jolt</groupId>` and every `in.jolt` dependency groupId in all poms (parent, modules, examples, `jolt-archetype`). Java packages (`in.jolt.*`) can stay; they need not match the groupId.
2. Update the coordinates in README.md, llms.txt, llms-extras.txt (its copy in jolt-core too), the jolt-starter pom and the archetype descriptor, then run `mvn -B clean verify`.
3. JitPack coordinates are unaffected: they stay `com.github.THIRU-1074.custom-Http-Server:<module>`. Existing users of JitPack keep working; Central users use the new groupId.

## 2. Central account and token

1. Sign in at https://central.sonatype.com with GitHub or email.
2. Open Namespaces, add your namespace and complete the verification from step 1.
3. Open Account, Generate User Token. Keep the username and password it shows.

## 3. GPG key

```
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
gpg --keyserver keys.openpgp.org --send-keys <KEYID>
```

Central checks the public key on those servers. Back up the private key and its revocation certificate offline.

## 4. Maven settings

`~/.m2/settings.xml`, never committed:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

## 5. Release

1. Set the release version in all poms (drop any `-SNAPSHOT`), and tag `vX.Y.Z`.
2. Export the passphrase: `export GPG_PASSPHRASE=...` (the profile passes `--pinentry-mode loopback` and reads this variable).
3. `mvn -B -P release clean deploy`
4. The bundle uploads with `autoPublish` off. Open https://central.sonatype.com/publishing/deployments, check it validated, then click Publish.
5. The examples and `jolt-archetype` are in the reactor, so check the bundle before publishing. Drop the example modules from the release build if you do not want them on Central.

## 6. Roll back

- A deployment that is validated but not yet published: click Drop in the Deployments page. Nothing is released.
- After publishing, Central versions are immutable and cannot be deleted. Fix forward: publish `X.Y.Z+1` and note the bad version in the release notes.
- A leaked token: revoke it in Account, then generate a new one. A leaked GPG key: revoke it with the saved certificate and publish a new key.

## Not verified by an agent

The deploy, signing, namespace verification and Central validation were not run. Only the profile's jar building was checked with `-Dgpg.skip=true`.
