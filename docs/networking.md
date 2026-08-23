# Networking

ViaLogium supports numerous custom packets for interacting with supported client mods

## Versions

The information on this page is applicable for ViaLogium Networking version 3, which is the version in ViaLogium versions `1.3.0` and later

## Packet Types

The server will not respond to packets unless the player has the correct permissions, which is `vialogium.networking` and the relevant command permission

#### Notation
Types shown here are the Java variable types. They have the equivalent value (if applicable) in Kotlin when used in ViaLogium's internal code

---
## Client to Server

### Response

Once one of the c2s packets have been received, the server will send a response packet with the packet type it was responding to and the response code. See below for more information

### Inspect Packet

Inspects a block at a given position, using the player's current dimension. This may be changed in the future

Channel: `vialogium:inspect`

Buf content:

Position: `BlockPos`

Number of pages: `int`

Return packet type: `vialogium.action`

### Search Packet

Channel: `vialogium:search`

Buf content:

Input: `String`

Pages: `int`

String formatted in the same way as a `/lg search` command would be formatted

Return packet type: `vialogium.action`

### Handshake Packet

Channel: `vialogium:handshake`

Buf content:

Mod NBT: `NbtCompound`

Mod NBT should contain the following:

- Mod Version (`version`) [`String`] : Fabric Loader user friendly string

- Mod ID (`modid`) [`String`] : Mod identifier of the mod

- Protocol version (`protocol_version`) [`int`] : ViaLogium protocol version

### Purge Packet

Channel: `vialogium.purge`

Buf content:

Params: `String` - same string as used in the search command

### Rollback Packet

Channel: `vialogium.rollback`

Buf content:

Restore: `Boolean` - To restore must be true, for to rollback must be false

Params: `String` - same string as used in the search command

---

## Server to client

### Action Packet

Represents a logged action from the database

Channel: `vialogium:action`

Buf content:

Position: `BlockPos`

Type: `String`

Dimension: `Identifier`

Old Object: `Identifier`

New Object: `Identifier`

Source: `String`

Epoch second: `long`

Rolled back: `boolean`

Additional NBT: `String`

### Handshake Packet

Sends information about ViaLogium to compatible clients

Channel: `vialogium:handshake`

Buf content:

Protocol Version: `int` - Version of ViaLogium networking protocol. ViaLogium `1.1.0` and later uses version `1`

Mod allowed: `boolean`

## Response

Registers the server receiving a ViaLogium packet and contains information about what the server is doing

Channel: `vialogium.response`

### Packet structure

Type: `Identifier` - Packet type being responded to

Response code: `int`

### Response Codes

`0`: No permission

`1`: Executing command

`2`: Completed command

`3`: Error while executing command

`4`: Cannot execute command at this time

