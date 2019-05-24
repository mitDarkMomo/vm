package one.inve.contract.MVM;


public class Main {
    public static void main(String[] args) {
//        String cfgDir = "/home/syl/Documents/data/";
//        String dbPath = "/home/syl/Documents/data/database";
        String cfgDir = "/home/ginvip/.config/Hashnet/configdata/";
        String dbPath = "/home/ginvip/.config/Hashnet/database";
        String dbId = "0_6";
        String address = "NUOX47THDUFUT7Z6XPNN75YJYRJK2LV";

        Boolean result = WorldStateService.isAddressExist(cfgDir, dbPath, "0_6", address);
        System.out.println("address '" + address + "' exists: " + result);

    }
}